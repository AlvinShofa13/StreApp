package com.studiopocket.source.remote

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RemoteNodeStatus { MENYAMBUNG, AKTIF, TERPUTUS, GALAT }

/**
 * Satu koneksi Node ke Host: membaca H.264, mendekode langsung ke SurfaceTexture
 * kompositor (tanpa salinan CPU), dan meneruskan PCM ke mixer Host.
 */
class RemoteNodeConnection(
    val nodeId: String,
    val nodeName: String,
    private val socket: Socket,
    token: String,
    private val textureProvider: () -> SurfaceTexture?,
    private val onPcm: (ShortArray, Int) -> Unit,
    private val onStatus: (RemoteNodeStatus) -> Unit,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null,
) {
    private val channel = SecureChannel(socket.getInputStream(), socket.getOutputStream(), token)
    private val decoderLock = Any()
    private var decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private var decoderTexture: SurfaceTexture? = null
    private var videoConfig: ByteArray? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var surfaceDirty = false

    private val audioBuffer = ShortArray(4096)

    fun start() {
        if (running) return
        running = true
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
        } catch (_: Throwable) {
        }
        onStatus(RemoteNodeStatus.MENYAMBUNG)
        thread = Thread({ bacaLoop() }, "SP-RemoteNode-$nodeId").apply { start() }
    }

    /** Dipanggil saat compositor memasang SurfaceTexture baru. */
    fun refreshSurface() {
        surfaceDirty = true
    }

    fun sendControl(control: RemoteControl) {
        try {
            channel.send(RemoteFrame.CONTROL, control.toJson().toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Kontrol ke Node gagal: ${t.message}")
        }
    }

    private fun bacaLoop() {
        var aktif = false
        try {
            while (running) {
                val frame = channel.receive() ?: break
                when (frame.type) {
                    RemoteFrame.VIDEO_CONFIG -> {
                        videoConfig = frame.payload
                        val b = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN)
                        if (frame.payload.size >= 8) {
                            val newW = b.int
                            val newH = b.int
                            if (newW > 0 && newH > 0 && (newW != videoWidth || newH != videoHeight)) {
                                videoWidth = newW
                                videoHeight = newH
                                surfaceDirty = true
                            }
                        }
                        siapkanDecoder()
                    }

                    RemoteFrame.VIDEO_SAMPLE -> {
                        if (siapkanDecoder()) {
                            antreVideo(frame.payload)
                            if (!aktif) {
                                aktif = true
                                onStatus(RemoteNodeStatus.AKTIF)
                            }
                        }
                    }

                    RemoteFrame.AUDIO_PCM -> teruskanAudio(frame.payload)
                    RemoteFrame.PING -> Unit
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Koneksi Node $nodeName berakhir: ${t.message}")
        } finally {
            tutup()
            onStatus(RemoteNodeStatus.TERPUTUS)
        }
    }

    private fun teruskanAudio(payload: ByteArray) {
        val samples = payload.size / 2
        if (samples <= 0) return
        val buffer = if (samples <= audioBuffer.size) audioBuffer else ShortArray(samples)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer, 0, samples)
        onPcm(buffer, samples / 2)
    }

    private fun siapkanDecoder(): Boolean {
        val texture = textureProvider()
        if (texture == null) return false
        synchronized(decoderLock) {
            if (decoder != null && decoderTexture === texture && !surfaceDirty) return true
            val config = videoConfig ?: return false
            lepasDecoder()
            try {
                val b = ByteBuffer.wrap(config).order(ByteOrder.BIG_ENDIAN)
                val width = b.int
                val height = b.int
                val spsLen = b.int
                if (spsLen <= 0 || spsLen > b.remaining()) return false
                val sps = ByteArray(spsLen).also { b.get(it) }
                val ppsLen = b.int
                if (ppsLen < 0 || ppsLen > b.remaining()) return false
                val pps = if (ppsLen > 0) ByteArray(ppsLen).also { b.get(it) } else ByteArray(0)

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                if (pps.isNotEmpty()) {
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val surface = Surface(texture)
                codec.configure(format, surface, null, 0)
                codec.start()
                decoder = codec
                decoderSurface = surface
                decoderTexture = texture
                videoWidth = width
                videoHeight = height
                surfaceDirty = false
                onVideoSizeChanged?.invoke(width, height)
                Log.i(TAG, "Decoder Node $nodeName siap ($width x $height)")
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal membuat decoder Node", t)
                lepasDecoder()
                return false
            }
        }
    }

    private fun antreVideo(payload: ByteArray) {
        if (payload.size < 9) return
        val ptsUs = ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.BIG_ENDIAN).long
        val flags = payload[8].toInt()
        synchronized(decoderLock) {
            val codec = decoder ?: return
            try {
                val index = codec.dequeueInputBuffer(5_000L)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: return
                    buffer.clear()
                    buffer.put(payload, 9, payload.size - 9)
                    codec.queueInputBuffer(
                        index,
                        0,
                        payload.size - 9,
                        ptsUs,
                        if (flags and 1 != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                }
                keluarkanOutput(codec)
            } catch (t: Throwable) {
                Log.w(TAG, "Galat decode Node: ${t.message}")
            }
        }
    }

    private fun keluarkanOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Format decoder Node: ${codec.outputFormat}")
            } else if (index >= 0) {
                codec.releaseOutputBuffer(index, true)
            } else {
                break
            }
        }
    }

    private fun lepasDecoder() {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (_: Throwable) {
        }
        decoder = null
        try {
            decoderSurface?.release()
        } catch (_: Throwable) {
        }
        decoderSurface = null
        decoderTexture = null
    }

    fun close() {
        running = false
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        thread?.interrupt()
        thread = null
    }

    private fun tutup() {
        running = false
        synchronized(decoderLock) { lepasDecoder() }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "SP-RemoteNode"
    }
}

/**
 * Sumber biasa di scene Host. Koneksi Node dapat datang, putus, lalu datang lagi
 * tanpa mengganti objek sumber atau menghentikan rekaman.
 */
class RemoteSource(
    override val id: String,
    override val name: String,
    private val onPcm: (ShortArray, Int) -> Unit,
) : Source {
    override var status: SourceStatus = SourceStatus.SIAGA
        private set
    override var statusListener: Source.StatusListener? = null
    override var frameSink: FrameSink? = null

    @Volatile
    var previewTexture: SurfaceTexture? = null
        private set

    var connection: RemoteNodeConnection? = null
        private set

    private val webRenderLock = Any()
    private var webSurface: Surface? = null
    private var webSurfaceTexture: SurfaceTexture? = null
    private var lastWebW = 0
    private var lastWebH = 0

    fun setPreview(texture: SurfaceTexture?) {
        previewTexture = texture
        synchronized(webRenderLock) {
            if (webSurfaceTexture !== texture) {
                try { webSurface?.release() } catch (_: Throwable) {}
                webSurface = null
                webSurfaceTexture = null
            }
        }
        connection?.refreshSurface()
    }

    /** Merender frame JPEG dari jembatan StreApp Web LAN langsung ke Surface kompositor. */
    fun renderWebJpegFrame(jpegBytes: ByteArray, onSizeChanged: ((Int, Int) -> Unit)? = null) {
        val texture = previewTexture ?: return
        val bmp = try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            synchronized(webRenderLock) {
                if (bmp.width != lastWebW || bmp.height != lastWebH) {
                    lastWebW = bmp.width
                    lastWebH = bmp.height
                    texture.setDefaultBufferSize(bmp.width, bmp.height)
                    onSizeChanged?.invoke(bmp.width, bmp.height)
                }
                if (webSurface == null || webSurfaceTexture !== texture) {
                    try { webSurface?.release() } catch (_: Throwable) {}
                    webSurface = Surface(texture)
                    webSurfaceTexture = texture
                }
                val s = webSurface ?: return
                val canvas = s.lockCanvas(null)
                try {
                    canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), null)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
            }
            setStatus(SourceStatus.AKTIF)
        } catch (t: Throwable) {
            Log.w("SP-RemoteSource", "Gagal merender frame Web LAN: ${t.message}")
        } finally {
            bmp.recycle()
        }
    }

    fun attach(connection: RemoteNodeConnection) {
        this.connection?.close()
        this.connection = connection
        setStatus(SourceStatus.AKTIF)
    }

    fun onConnectionLost() {
        setStatus(SourceStatus.TERPUTUS)
    }

    override fun start() {
        setStatus(if (connection != null) SourceStatus.AKTIF else SourceStatus.SIAGA)
    }

    override fun stop() {
        connection?.close()
        connection = null
        synchronized(webRenderLock) {
            try { webSurface?.release() } catch (_: Throwable) {}
            webSurface = null
            webSurfaceTexture = null
        }
        previewTexture = null
        setStatus(SourceStatus.SIAGA)
    }

    private fun setStatus(nilai: SourceStatus) {
        if (status == nilai) return
        status = nilai
        statusListener?.onStatusChanged(this, nilai)
    }
}
