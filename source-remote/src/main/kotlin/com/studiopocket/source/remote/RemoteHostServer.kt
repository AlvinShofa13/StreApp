package com.studiopocket.source.remote

import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** Mencari alamat IPv4 lokal untuk QR pairing (Wi-Fi biasa maupun hotspot). */
object NetworkAddress {
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Server pairing di Host. Menampilkan token di QR; setiap token hanya untuk satu sesi
 * pairing. Node yang tidak membawa token benar gagal didekripsi dan langsung ditutup.
 */
class RemoteHostServer(
    private val hostName: String,
    private val preferredPort: Int = 45678,
) {
    @Volatile
    var pairing: PairingInfo = buatPairing()
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
        val socket = try {
            ServerSocket(preferredPort)
        } catch (_: Throwable) {
            try {
                ServerSocket(0)
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal membuka ServerSocket: ${t.message}", t)
                throw t
            }
        }
        running = true
        server = socket
        pairing = buatPairing(socket.localPort)
        thread = Thread({ terimaLoop(socket) }, "SP-RemoteHost").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Server Node di port ${socket.localPort}")
        return pairing
    }

    /** Token baru untuk pasangan Node berikutnya. */
    fun rotateToken(): PairingInfo {
        pairing = buatPairing(server?.localPort ?: preferredPort)
        return pairing
    }

    private fun buatPairing(port: Int = preferredPort): PairingInfo {
        val random = SecureRandom()
        val token = buildString {
            repeat(8) { append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)]) }
        }
        return PairingInfo(
            host = NetworkAddress.localIpv4() ?: "0.0.0.0",
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
            val nodeId = json.optString("nodeId").ifEmpty { socket.inetAddress.hostAddress }
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
