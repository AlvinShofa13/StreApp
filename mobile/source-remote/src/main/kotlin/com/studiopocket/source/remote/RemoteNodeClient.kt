package com.studiopocket.source.remote

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Klien Camera Node. Mengirim H.264 dan PCM ke Host lewat kanal terenkripsi,
 * menyambung ulang hingga dihentikan, dan menerima kontrol dari Host.
 * Sampel yang datang saat jaringan putus dibuang; pipeline kamera tidak pernah tertahan.
 */
class RemoteNodeClient(
    private val pairing: PairingInfo,
    private val nodeName: String,
) {
    var onControl: ((RemoteControl) -> Unit)? = null
    var onStatus: ((RemoteNodeStatus) -> Unit)? = null

    @Volatile
    var connected = false
        private set

    private val nodeId = java.util.UUID.randomUUID().toString().take(8)
    private val queue = ArrayDeque<Pair<Int, ByteArray>>()
    private val queueLock = Any()
    private var thread: Thread? = null
    private var reader: Thread? = null
    private var socket: Socket? = null
    private var channel: SecureChannel? = null
    private var videoConfig: ByteArray? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "SP-NodeClient").apply { start() }
    }

    fun stop() {
        running = false
        connected = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        thread?.interrupt()
        reader?.interrupt()
        thread = null
        reader = null
        synchronized(queueLock) { queue.clear() }
        onStatus?.invoke(RemoteNodeStatus.TERPUTUS)
    }

    fun sendVideoConfig(width: Int, height: Int, sps: ByteArray, pps: ByteArray) {
        val data = encodeVideoConfig(width, height, sps, pps)
        videoConfig = data
        tawarkan(RemoteFrame.VIDEO_CONFIG, data)
    }

    fun sendVideoSample(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        val payload = ByteArray(9 + data.size)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putLong(ptsUs)
        payload[8] = if (keyframe) 1 else 0
        System.arraycopy(data, 0, payload, 9, data.size)
        tawarkan(RemoteFrame.VIDEO_SAMPLE, payload)
    }

    fun sendAudio(pcm: ShortArray, frames: Int) {
        if (!connected) return
        val payload = ByteArray(frames * 4)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, frames * 2)
        tawarkan(RemoteFrame.AUDIO_PCM, payload)
    }

    private fun tawarkan(type: Int, payload: ByteArray) {
        if (!connected) return
        synchronized(queueLock) {
            if (queue.size > 90) queue.removeFirst()
            queue.addLast(type to payload)
        }
    }

    private fun loop() {
        while (running) {
            try {
                sambungSekali()
            } catch (t: Throwable) {
                if (!running) break
                connected = false
                onStatus?.invoke(RemoteNodeStatus.TERPUTUS)
                Log.w(TAG, "Node gagal menyambung: ${t.message}")
            } finally {
                connected = false
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
                socket = null
                channel = null
            }
            if (!running) break
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
        onStatus?.invoke(RemoteNodeStatus.TERPUTUS)
    }

    private fun sambungSekali() {
        onStatus?.invoke(RemoteNodeStatus.MENYAMBUNG)
        val sock = Socket()
        socket = sock
        sock.connect(InetSocketAddress(pairing.host, pairing.port), 6000)
        sock.tcpNoDelay = true
        val ch = SecureChannel(sock.getInputStream(), sock.getOutputStream(), pairing.token)
        channel = ch
        ch.send(
            RemoteFrame.HANDSHAKE,
            "{\"nodeId\":\"$nodeId\",\"name\":${quote(nodeName)}}".toByteArray(Charsets.UTF_8),
        )
        connected = true
        onStatus?.invoke(RemoteNodeStatus.AKTIF)
        videoConfig?.let { ch.send(RemoteFrame.VIDEO_CONFIG, it) }

        reader = Thread({ bacaKontrol(ch) }, "SP-NodeControl").apply {
            isDaemon = true
            start()
        }

        while (running && connected) {
            val item = synchronized(queueLock) { queue.pollFirst() }
            if (item == null) {
                Thread.sleep(10)
                continue
            }
            ch.send(item.first, item.second)
        }
        throw IOException("Koneksi Node ditutup")
    }

    private fun bacaKontrol(ch: SecureChannel) {
        try {
            while (running) {
                val frame = ch.receive() ?: break
                if (frame.type == RemoteFrame.CONTROL) {
                    onControl?.invoke(parseControl(String(frame.payload, Charsets.UTF_8)))
                }
            }
        } catch (_: Throwable) {
        } finally {
            connected = false
        }
    }

    private fun parseControl(json: String): RemoteControl {
        val o = org.json.JSONObject(json)
        return RemoteControl(
            focusMode = o.optString("focusMode").ifEmpty { null },
            focusDistance = if (o.has("focusDistance")) o.optDouble("focusDistance").toFloat() else null,
            zoom = if (o.has("zoom")) o.optDouble("zoom").toFloat() else null,
            exposure = if (o.has("exposure")) o.optInt("exposure") else null,
            torch = if (o.has("torch")) o.optBoolean("torch") else null,
            audioEnabled = if (o.has("audio")) o.optBoolean("audio") else null,
            whiteBalance = o.optString("whiteBalance").ifEmpty { null },
        )
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val TAG = "SP-NodeClient"

        /**
         * Mengemas header konfigurasi video H.264: 4 Int (16 byte) + SPS + PPS (opsional).
         */
        fun encodeVideoConfig(width: Int, height: Int, sps: ByteArray, pps: ByteArray): ByteArray {
            val buffer = ByteBuffer.allocate(16 + sps.size + pps.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(width).putInt(height).putInt(sps.size).put(sps).putInt(pps.size).put(pps)
            return buffer.array()
        }
    }
}
