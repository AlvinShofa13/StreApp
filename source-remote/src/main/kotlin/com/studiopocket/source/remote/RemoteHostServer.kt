package com.studiopocket.source.remote

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

enum class NetworkLinkKind { USB_CABLE, WIFI_HOTSPOT, OTHER }

data class NetworkEndpoint(
    val ip: String,
    val interfaceName: String,
    val kind: NetworkLinkKind,
)

/**
 * Mencari alamat IPv4 lokal untuk QR pairing — mendukung Kabel USB (USB Tethering / RNDIS),
 * Hotspot, dan Wi-Fi, sambil menghindari alamat seluler (rmnet/ccmni) yang tidak bisa dijangkau LAN.
 */
object NetworkAddress {

    fun allLocalEndpoints(context: Context? = null): List<NetworkEndpoint> {
        val found = LinkedHashMap<String, NetworkEndpoint>()

        // 1. Pindai semua NetworkInterface (mendukung Wi-Fi, Hotspot ap0/swlan0, dan Kabel USB rndis0/usb0/ncm0)
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (ni in ifaces.toList()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.orEmpty().lowercase()
                    if (isCellularInterface(name)) continue
                    val kind = classifyInterface(name)
                    for (addr in ni.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip != "0.0.0.0" && !found.containsKey(ip)) {
                                found[ip] = NetworkEndpoint(ip, ni.name.orEmpty(), kind)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("SP-NetAddr", "Gagal membaca NetworkInterface: ${t.message}")
        }

        // 2. Cadangan lewat ConnectivityManager (beberapa perangkat Android 11+ membatasi getNetworkInterfaces)
        if (context != null) {
            try {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val active = cm?.activeNetwork
                val props = if (active != null) cm.getLinkProperties(active) else null
                val ifaceName = props?.interfaceName.orEmpty()
                if (props != null && !isCellularInterface(ifaceName.lowercase())) {
                    val kind = classifyInterface(ifaceName.lowercase())
                    for (linkAddr in props.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip != "0.0.0.0" && !found.containsKey(ip)) {
                                found[ip] = NetworkEndpoint(ip, ifaceName.ifBlank { "net" }, kind)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("SP-NetAddr", "Gagal membaca ConnectivityManager: ${t.message}")
            }
        }

        // Urutkan prioritas: Kabel USB (paling stabil & latensi terendah) -> Wi-Fi/Hotspot -> lainnya
        return found.values.sortedBy {
            when (it.kind) {
                NetworkLinkKind.USB_CABLE -> 0
                NetworkLinkKind.WIFI_HOTSPOT -> 1
                NetworkLinkKind.OTHER -> 2
            }
        }
    }

    fun localIpv4(context: Context? = null): String? =
        allLocalEndpoints(context).firstOrNull()?.ip

    private fun isCellularInterface(lowerName: String): Boolean =
        lowerName.startsWith("rmnet") ||
            lowerName.startsWith("ccmni") ||
            lowerName.startsWith("pdp") ||
            lowerName.startsWith("wwan") ||
            lowerName.startsWith("tun")

    private fun classifyInterface(lowerName: String): NetworkLinkKind = when {
        lowerName.startsWith("rndis") ||
            lowerName.startsWith("usb") ||
            lowerName.startsWith("ncm") ||
            lowerName.startsWith("eth") -> NetworkLinkKind.USB_CABLE

        lowerName.startsWith("wlan") ||
            lowerName.startsWith("ap") ||
            lowerName.startsWith("swlan") ||
            lowerName.startsWith("softap") ||
            lowerName.startsWith("p2p") -> NetworkLinkKind.WIFI_HOTSPOT

        else -> NetworkLinkKind.OTHER
    }
}

/**
 * Server pairing di Host. Menampilkan token di QR; setiap token hanya untuk satu sesi
 * pairing. Mendukung Wi-Fi, Hotspot, dan Kabel USB (USB Tethering / RNDIS).
 */
class RemoteHostServer(
    private val hostName: String,
    private val preferredPort: Int = 45678,
    private val appContext: Context? = null,
) {
    @Volatile
    var pairing: PairingInfo = buatPairing()
        private set

    @Volatile
    var availableEndpoints: List<NetworkEndpoint> = emptyList()
        private set

    /** Dibuat oleh lapisan aplikasi agar koneksi memakai SurfaceTexture kompositor yang sama. */
    var connectionFactory: ((nodeId: String, nodeName: String, socket: Socket, token: String) -> RemoteNodeConnection)? = null

    var onNodeConnected: ((RemoteNodeConnection) -> Unit)? = null
    var onPairingRejected: ((String) -> Unit)? = null

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val hostId = java.util.UUID.randomUUID().toString().take(8)

    @Volatile
    private var running = false

    fun start(): PairingInfo {
        if (running) return pairing
        val endpoints = NetworkAddress.allLocalEndpoints(appContext)
        availableEndpoints = endpoints
        val preferredIp = endpoints.firstOrNull()?.ip

        val socket = bukaServerSocket(preferredIp)
        running = true
        server = socket
        pairing = buatPairing(port = socket.localPort, forcedHost = preferredIp)
        thread = Thread({ terimaLoop(socket) }, "SP-RemoteHost").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Server Node di ${pairing.host}:${socket.localPort} (kandidat=${endpoints.size})")
        return pairing
    }

    private fun bukaServerSocket(preferredIp: String?): ServerSocket {
        val attempts = listOf<() -> ServerSocket>(
            {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort))
                }
            },
            {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
            },
            {
                val bindAddr = if (!preferredIp.isNullOrBlank() && preferredIp != "0.0.0.0") {
                    InetAddress.getByName(preferredIp)
                } else {
                    InetAddress.getLoopbackAddress()
                }
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddr, 0))
                }
            },
        )
        var lastError: Throwable? = null
        for (attempt in attempts) {
            try {
                return attempt()
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Percobaan bind ServerSocket gagal: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        throw lastError ?: IllegalStateException("Tidak dapat membuka ServerSocket")
    }

    /** Segarkan daftar antarmuka jaringan (Wi-Fi / Hotspot / Kabel USB) dan perbarui token. */
    fun rotateToken(): PairingInfo {
        val endpoints = NetworkAddress.allLocalEndpoints(appContext)
        availableEndpoints = endpoints
        val currentHost = pairing.host.takeIf { h -> endpoints.any { it.ip == h } }
            ?: endpoints.firstOrNull()?.ip
        pairing = buatPairing(port = server?.localPort ?: preferredPort, forcedHost = currentHost)
        return pairing
    }

    /** Ganti IP yang dipakai di QR (misalnya beralih antara Kabel USB dan Wi-Fi/Hotspot). */
    fun selectHostIp(ip: String): PairingInfo {
        if (ip.isBlank()) return pairing
        pairing = pairing.copy(host = ip)
        return pairing
    }

    private fun buatPairing(port: Int = preferredPort, forcedHost: String? = null): PairingInfo {
        val random = SecureRandom()
        val token = buildString {
            repeat(8) { append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)]) }
        }
        val ip = forcedHost ?: NetworkAddress.localIpv4(appContext) ?: "192.168.43.1"
        return PairingInfo(
            host = ip,
            port = port,
            token = token,
            hostId = hostId,
        )
    }

    private fun terimaLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ tangani(client) }, "SP-RemoteAccept").apply { isDaemon = true }.start()
        }
    }

    private fun tangani(socket: Socket) {
        val token = pairing.token
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val channel = SecureChannel(socket.getInputStream(), socket.getOutputStream(), token)
            val handshake = channel.receive() ?: run {
                socket.close()
                return
            }
            if (handshake.type != RemoteFrame.HANDSHAKE) {
                socket.close()
                return
            }
            val json = JSONObject(String(handshake.payload, Charsets.UTF_8))
            val nodeId = json.optString("nodeId").ifEmpty { socket.inetAddress.hostAddress ?: "node" }
            val nodeName = json.optString("name", "Camera Node")
            val factory = connectionFactory
            if (factory == null) {
                onPairingRejected?.invoke("Mode Node belum siap di Host")
                socket.close()
                return
            }
            socket.soTimeout = 30_000
            val connection = factory(nodeId, nodeName, socket, token)
            onNodeConnected?.invoke(connection)
            connection.start()
            Log.i(TAG, "Node $nodeName ($nodeId) tersambung")
        } catch (t: Throwable) {
            Log.w(TAG, "Pairing ditolak: ${t.message}")
            onPairingRejected?.invoke("Token salah atau koneksi tidak sah")
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        thread = null
    }

    companion object {
        private const val TAG = "SP-RemoteHost"
    }
}
