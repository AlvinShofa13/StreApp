package com.studiopocket.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import javax.net.ssl.SSLSocketFactory

/**
 * Klien RTMP/RTMPS tulis saja. Satu koneksi mengirim video dan audio hasil encoder
 * yang sama dengan rekaman lokal. Menyambung ulang otomatis dan membuang sampel saat
 * jaringan terputus agar rekaman lokal tidak pernah terganggu.
 */
class RtmpPublisher(
    private val url: String,
    private val streamKey: String,
) {
    enum class Status { MENYAMBUNG, SIARAN, TERPUTUS, GALAT, BERHENTI }

    data class Packet(val type: Int, val timestampMs: Long, val data: ByteArray, val keyframe: Boolean)

    @Volatile
    var listener: ((Status, String) -> Unit)? = null

    /** kbps terkirim dalam jendela 1 detik terakhir. */
    @Volatile
    var onStats: ((Int) -> Unit)? = null

    @Volatile
    var running = false
        private set

    /** Bitrate saran dari laju kirim aktual; 0 bila belum tersedia. */
    @Volatile
    var suggestedBitrateKbps: Int = 0
        private set

    @Volatile
    var droppedSamples: Long = 0
        private set

    private val queue = ArrayDeque<Packet>()
    private val queueLock = Any()
    private val sendLock = Any()
    private var thread: Thread? = null
    private var socket: Socket? = null

    @Volatile
    private var connected = false

    @Volatile
    private var videoConfig: ByteArray? = null

    @Volatile
    private var audioConfig: ByteArray? = null

    private var basePtsUs = -1L
    private var sentBytesInWindow = 0L
    private var windowStartMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoFps = 30
    private var videoBitrateKbps = 0

    private var lastStatus: Status? = null

    fun start() {
        if (running) return
        running = true
        basePtsUs = -1L
        thread = Thread({ loopKoneksi() }, "SP-RtmpPublisher").apply { start() }
        Log.i(TAG, "RTMP mulai: ${url.substringBeforeLast('/')}")
    }

    fun stop() {
        running = false
        connected = false
        tutupSocket()
        thread?.join(1500)
        thread = null
        synchronized(queueLock) { queue.clear() }
        lapor(Status.BERHENTI, "Siaran dihentikan")
    }

    fun setVideoFormat(format: MediaFormat) {
        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH, 0)
        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
        format.getInteger(MediaFormat.KEY_FRAME_RATE, 0).let { if (it > 0) videoFps = it }
        format.getInteger(MediaFormat.KEY_BIT_RATE, 0).let { if (it > 0) videoBitrateKbps = it / 1000 }
        val sps = format.getByteBuffer("csd-0")?.let { firstNal(it) }
        val pps = format.getByteBuffer("csd-1")?.let { firstNal(it) }
        if (sps == null || pps == null || sps.size < 4) {
            Log.w(TAG, "Format video tanpa csd SPS/PPS")
            return
        }
        val avcC = ByteArrayOutputStream().apply {
            write(1)
            write(sps[1].toInt())
            write(sps[2].toInt())
            write(sps[3].toInt())
            write(0xFF)
            write(0xE1)
            write((sps.size shr 8) and 0xFF)
            write(sps.size and 0xFF)
            write(sps)
            write(1)
            write((pps.size shr 8) and 0xFF)
            write(pps.size and 0xFF)
            write(pps)
        }.toByteArray()
        val header = ByteArray(5 + avcC.size)
        header[0] = 0x17
        header[1] = 0x00
        System.arraycopy(avcC, 0, header, 5, avcC.size)
        videoConfig = header
    }

    fun setAudioFormat(format: MediaFormat) {
        val csd = format.getByteBuffer("csd-0")?.let {
            val data = ByteArray(it.remaining())
            it.duplicate().get(data)
            data
        } ?: return
        audioConfig = byteArrayOf(0xAF.toByte(), 0x00) + csd
    }

    fun writeVideo(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!running) return
        val data = ByteArray(info.size)
        val dup = buffer.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        dup.get(data)
        tawarkan(Packet(TYPE_VIDEO, info.presentationTimeUs / 1000, data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
    }

    fun writeAudio(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!running) return
        val data = ByteArray(info.size)
        val dup = buffer.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        dup.get(data)
        tawarkan(Packet(TYPE_AUDIO, info.presentationTimeUs / 1000, data, false))
    }

    private fun tawarkan(packet: Packet) {
        if (!connected) {
            droppedSamples++
            return
        }
        synchronized(queueLock) {
            if (queue.size > 180) {
                queue.removeFirst()
                droppedSamples++
            }
            queue.addLast(packet)
        }
    }

    /** Format audio/video diberikan sekali setelah encoder siap. */
    fun metadata(): ByteArray? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val out = ByteArrayOutputStream()
        Amf0.writeString(out, "@setDataFrame")
        Amf0.writeString(out, "onMetaData")
        Amf0.writeObject(
            out,
            mapOf(
                "width" to videoWidth,
                "height" to videoHeight,
                "framerate" to videoFps.toDouble(),
                "videocodecid" to 7.0,
                "audiocodecid" to 10.0,
                "videodatarate" to videoBitrateKbps.toDouble(),
                "encoder" to "StreApp",
            ),
        )
        return out.toByteArray()
    }

    private fun loopKoneksi() {
        var percobaan = 0
        while (running) {
            try {
                koneksiSekali()
            } catch (t: Throwable) {
                if (!running) break
                connected = false
                droppedSamples++
                Log.w(TAG, "Koneksi RTMP gagal: ${t.message}")
                lapor(Status.TERPUTUS, t.message ?: "Jaringan terputus, mencoba lagi")
            } finally {
                connected = false
                tutupSocket()
                synchronized(queueLock) { queue.clear() }
            }
            if (!running) break
            percobaan++
            lapor(Status.MENYAMBUNG, "Menyambung ulang (percobaan $percobaan)")
            val jeda = (1000L + percobaan * 1000L).coerceAtMost(5000L)
            try {
                Thread.sleep(jeda)
            } catch (_: InterruptedException) {
                break
            }
        }
        lapor(Status.BERHENTI, "Siaran berakhir")
    }

    private fun koneksiSekali() {
        lapor(Status.MENYAMBUNG, "Menyambung ke server")
        val uri = URI(url)
        val secure = uri.scheme.equals("rtmps", ignoreCase = true)
        val host = uri.host ?: throw IOException("Alamat RTMP tidak sah")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 1935
        val app = uri.path.trim('/').ifEmpty { "live" }
        val sock = if (secure) {
            SSLSocketFactory.getDefault().createSocket(host, port) as Socket
        } else {
            Socket()
        }
        socket = sock
        sock.connect(InetSocketAddress(host, port), 8000)
        sock.soTimeout = 0
        sock.tcpNoDelay = true
        val input = DataInputStream(sock.getInputStream())
        val output = sock.getOutputStream()

        handshake(input, output)
        val koneksi = RtmpConnection(input, output)
        koneksi.chunkSize = 4096
        koneksi.kirim(RtmpMessage(2, 1, 0, 0, intBytes(4096)))

        val connect = ByteArrayOutputStream().apply {
            Amf0.writeString(this, "connect")
            Amf0.writeNumber(this, 1.0)
            Amf0.writeObject(
                this,
                mapOf(
                    "app" to app,
                    "type" to "nonprivate",
                    "flashVer" to "FMLE/3.0 (compatible; StreApp)",
                    "tcUrl" to url.substringBeforeLast('/'),
                    "fpad" to false,
                    "capabilities" to 15.0,
                    "audioCodecs" to 4071.0,
                    "videoCodecs" to 252.0,
                    "videoFunction" to 1.0,
                    "objectEncoding" to 0.0,
                ),
            )
        }.toByteArray()
        koneksi.kirim(RtmpMessage(3, 20, 0, 0, connect))
        koneksi.harapkanPerintah("_result", 1.0, 8000)

        koneksi.kirim(perintah("releaseStream", 2.0, null, streamKey))
        koneksi.kirim(perintah("FCPublish", 3.0, null, streamKey))
        koneksi.kirim(perintah("createStream", 4.0, null))

        val hasil = koneksi.harapkanPerintah("_result", 4.0, 8000)
        val streamId = (hasil.getOrNull(2) as? Double)?.toInt() ?: 1

        val publish = ByteArrayOutputStream().apply {
            Amf0.writeString(this, "publish")
            Amf0.writeNumber(this, 0.0)
            Amf0.writeNull(this)
            Amf0.writeString(this, streamKey)
            Amf0.writeString(this, "live")
        }.toByteArray()
        koneksi.kirim(RtmpMessage(4, 20, streamId, 0, publish))

        val metadata = metadata()
        if (metadata != null) koneksi.kirim(RtmpMessage(4, 18, streamId, 0, metadata))

        connected = true
        basePtsUs = -1L
        videoConfigTerkirim = false
        windowStartMs = System.currentTimeMillis()
        sentBytesInWindow = 0
        lapor(Status.SIARAN, "Siaran aktif")

        videoConfig?.let { koneksi.kirim(RtmpMessage(6, TYPE_VIDEO, streamId, 0, it)) }
        videoConfigTerkirim = videoConfig != null
        audioConfig?.let { koneksi.kirim(RtmpMessage(7, TYPE_AUDIO, streamId, 0, it)) }

        val pembaca = koneksi.pembacaLatar { event ->
            if (event is RtmpEvent.Info && (event.code.contains("BadName") || event.code.contains("Failed"))) {
                connected = false
                lapor(Status.GALAT, event.description.ifBlank { "Stream key ditolak server" })
            }
        }

        while (running && connected) {
            val packet = synchronized(queueLock) { queue.pollFirst() }
            if (packet == null) {
                Thread.sleep(10)
                perbaruiStatistik()
                continue
            }
            if (packet.type == TYPE_VIDEO && packet.keyframe && videoConfig != null && !videoConfigTerkirim) {
                koneksi.kirim(RtmpMessage(6, TYPE_VIDEO, streamId, 0, videoConfig!!))
                videoConfigTerkirim = true
            }
            val ts = if (basePtsUs < 0) {
                basePtsUs = packet.timestampMs * 1000
                0L
            } else {
                (packet.timestampMs * 1000 - basePtsUs).coerceAtLeast(0) / 1000
            }
            koneksi.kirim(RtmpMessage(if (packet.type == TYPE_VIDEO) 6 else 7, packet.type, streamId, ts.toInt(), packet.data))
            sentBytesInWindow += packet.data.size + 16
            perbaruiStatistik()
        }
        pembaca?.interrupt()
        koneksi.tutup()
    }

    private var videoConfigTerkirim = false

    private fun perbaruiStatistik() {
        val now = System.currentTimeMillis()
        val lewat = now - windowStartMs
        if (lewat >= 1000) {
            val kbps = (sentBytesInWindow * 8 / lewat).toInt()
            suggestedBitrateKbps = if (kbps > 0) kbps else suggestedBitrateKbps
            onStats?.invoke(kbps)
            sentBytesInWindow = 0
            windowStartMs = now
        }
    }

    private fun lapor(status: Status, pesan: String) {
        if (lastStatus == status && status != Status.MENYAMBUNG) return
        lastStatus = status
        listener?.invoke(status, pesan)
    }

    private fun tutupSocket() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
    }

    private fun handshake(input: DataInputStream, output: OutputStream) {
        val c1 = ByteArray(1536).also { java.security.SecureRandom().nextBytes(it) }
        for (i in 0 until 4) c1[i] = 0
        output.write(3)
        output.write(c1)
        output.flush()
        val s0 = input.readUnsignedByte()
        if (s0 != 3) throw IOException("RTMP versi server $s0 tidak didukung")
        val s1 = ByteArray(1536)
        val s2 = ByteArray(1536)
        input.readFully(s1)
        input.readFully(s2)
        output.write(s1)
        output.flush()
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun perintah(nama: String, transaksi: Double, vararg argumen: String?): RtmpMessage {
        val out = ByteArrayOutputStream()
        Amf0.writeString(out, nama)
        Amf0.writeNumber(out, transaksi)
        for (a in argumen) if (a == null) Amf0.writeNull(out) else Amf0.writeString(out, a)
        return RtmpMessage(3, 20, 0, 0, out.toByteArray())
    }

    private fun firstNal(buffer: java.nio.ByteBuffer): ByteArray {
        val data = ByteArray(buffer.remaining())
        buffer.duplicate().get(data)
        var start = 0
        while (start + 3 < data.size) {
            if (data[start] == 0.toByte() && data[start + 1] == 0.toByte()) {
                if (data[start + 2] == 1.toByte()) {
                    start += 3
                    break
                }
                if (start + 3 < data.size && data[start + 2] == 0.toByte() && data[start + 3] == 1.toByte()) {
                    start += 4
                    break
                }
            }
            start++
        }
        var end = data.size
        var i = start
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
            ) {
                end = i
                break
            }
            i++
        }
        return data.copyOfRange(start, end)
    }

    companion object {
        private const val TAG = "SP-Rtmp"
        private const val TYPE_AUDIO = 8
        private const val TYPE_VIDEO = 9
    }
}

/** Pengirim dan pembaca pesan RTMP dengan chunking 4096. */
internal class RtmpConnection(
    private val input: DataInputStream,
    private val output: OutputStream,
) {
    @Volatile
    var chunkSize = 128

    private val kunciKirim = Any()
    private val states = HashMap<Int, ChunkState>()

    private class ChunkState {
        var timestamp = 0L
        var length = 0
        var type = 0
        var streamId = 0
        var buffer = ByteArray(0)
        var terisi = 0
    }

    fun kirim(message: RtmpMessage) {
        synchronized(kunciKirim) {
            val header = ByteArrayOutputStream()
            header.write(message.csid)
            val ts = message.timestamp.toLong() and 0xFFFFFF
            header.write((ts shr 16).toInt())
            header.write((ts shr 8).toInt())
            header.write(ts.toInt())
            header.write((message.payload.size shr 16) and 0xFF)
            header.write((message.payload.size shr 8) and 0xFF)
            header.write(message.payload.size and 0xFF)
            header.write(message.type)
            val sid = message.streamId
            header.write(sid and 0xFF)
            header.write((sid shr 8) and 0xFF)
            header.write((sid shr 16) and 0xFF)
            header.write((sid shr 24) and 0xFF)
            output.write(header.toByteArray())
            var offset = 0
            var first = true
            while (offset < message.payload.size) {
                val ambil = minOf(chunkSize, message.payload.size - offset)
                if (!first) {
                    output.write(0xC0 or message.csid)
                    val delta = 0
                    output.write(delta)
                    output.write(delta)
                    output.write(delta)
                }
                output.write(message.payload, offset, ambil)
                offset += ambil
                first = false
            }
            output.flush()
        }
    }

    /** Mengembalikan daftar nilai perintah untuk transaksi tertentu. */
    fun harapkanPerintah(nama: String, transaksi: Double, timeoutMs: Long): List<Any?> {
        val batas = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < batas) {
            val pesan = bacaPesan()
            if (pesan.type == 20 || pesan.type == 17) {
                val nilai = bacaNilai(pesan.payload)
                if (nilai.firstOrNull() == nama) {
                    val tid = nilai.getOrNull(1) as? Double
                    if (tid == null || tid == transaksi) return nilai
                }
            }
        }
        throw IOException("Timeout menunggu $nama")
    }

    private fun bacaNilai(payload: ByteArray): List<Any?> {
        val stream = DataInputStream(payload.inputStream())
        val hasil = ArrayList<Any?>()
        return try {
            while (stream.available() > 0) {
                hasil.add(Amf0.readValue(stream))
            }
            hasil
        } catch (_: Throwable) {
            hasil
        }
    }

    private fun bacaPesan(): RtmpMessage {
        while (true) {
            val pertama = input.readUnsignedByte()
            val fmt = pertama ushr 6
            var csid = pertama and 0x3F
            if (csid == 0) csid = input.readUnsignedByte() + 64
            else if (csid == 1) csid = input.readUnsignedShort() + 64
            val state = states.getOrPut(csid) { ChunkState() }
            var timestamp = state.timestamp
            when (fmt) {
                0 -> {
                    timestamp = bacaTiga()
                    state.timestamp = timestamp
                    state.length = bacaTiga().toInt()
                    state.type = input.readUnsignedByte()
                    state.streamId = input.readInt().let { Integer.reverseBytes(it) }
                    state.buffer = ByteArray(state.length)
                    state.terisi = 0
                }

                1 -> {
                    timestamp = state.timestamp + bacaTiga()
                    state.timestamp = timestamp
                    state.length = bacaTiga().toInt()
                    state.type = input.readUnsignedByte()
                    state.buffer = ByteArray(state.length)
                    state.terisi = 0
                }

                2 -> {
                    timestamp = state.timestamp + bacaTiga()
                    state.timestamp = timestamp
                    state.buffer = ByteArray(state.length)
                    state.terisi = 0
                }

                3 -> {
                    if (state.terisi == 0) {
                        state.buffer = ByteArray(state.length)
                    }
                }
            }
            val sisa = state.length - state.terisi
            val ambil = minOf(sisa, chunkSize)
            if (state.terisi + ambil > state.buffer.size) {
                state.buffer = state.buffer.copyOf(state.terisi + ambil)
            }
            input.readFully(state.buffer, state.terisi, ambil)
            state.terisi += ambil
            if (state.terisi >= state.length) {
                val payload = state.buffer.copyOf(state.length)
                state.terisi = 0
                return RtmpMessage(csid, state.type, state.streamId, timestamp.toInt(), payload)
            }
        }
    }

    private fun bacaTiga(): Long =
        ((input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()).toLong()

    fun pembacaLatar(onEvent: (RtmpEvent) -> Unit): Thread? {
        val t = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val pesan = bacaPesan()
                    when (pesan.type) {
                        1 -> if (pesan.payload.size >= 4) {
                            chunkSize = ((pesan.payload[0].toInt() and 0xFF) shl 24) or
                                ((pesan.payload[1].toInt() and 0xFF) shl 16) or
                                ((pesan.payload[2].toInt() and 0xFF) shl 8) or
                                (pesan.payload[3].toInt() and 0xFF)
                        }

                        4 -> {
                            if (pesan.payload.size >= 6) {
                                val event = ((pesan.payload[0].toInt() and 0xFF) shl 8) or (pesan.payload[1].toInt() and 0xFF)
                                if (event == 6) {
                                    val balas = byteArrayOf(0, 7) + pesan.payload.copyOfRange(2, 6)
                                    kirim(RtmpMessage(2, 4, 0, 0, balas))
                                }
                            }
                        }

                        20, 17 -> {
                            val nilai = bacaNilai(pesan.payload)
                            val nama = nilai.firstOrNull() as? String
                            if (nama == "onStatus") {
                                val info = nilai.getOrNull(3) as? Map<*, *>
                                val kode = info?.get("code") as? String ?: ""
                                onEvent(RtmpEvent.Info(kode, info?.get("description") as? String ?: ""))
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }, "SP-RtmpReader")
        t.isDaemon = true
        t.start()
        return t
    }

    fun tutup() {
        try {
            input.close()
        } catch (_: Throwable) {
        }
        try {
            output.close()
        } catch (_: Throwable) {
        }
    }
}

sealed class RtmpEvent {
    data class Info(val code: String, val description: String) : RtmpEvent()
}
