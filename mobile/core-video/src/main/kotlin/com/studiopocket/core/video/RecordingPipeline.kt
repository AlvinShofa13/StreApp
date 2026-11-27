package com.studiopocket.core.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.studiopocket.core.audio.AudioEncoder
import com.studiopocket.core.audio.AudioRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Penerima sampel terenkode tambahan (misalnya pengirim RTMP) dari encoder yang sama. */
interface EncodedSink {
    fun onVideoFormat(format: MediaFormat)
    fun onVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun onAudioFormat(format: MediaFormat)
    fun onAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/**
 * Pipeline perekaman gabungan video (H.264) dan audio (AAC) berkala (Segmented MP4).
 * Menjamin pemulihan file rekaman jika aplikasi dimatikan paksa (crash / force stop).
 *
 * [useBuiltinMic] false bila audio datang dari AudioEngine lewat [feedAudio]; dengan begitu
 * seluruh sumber audio tetap tercampur di satu track encoder yang sama dengan siaran.
 */
class RecordingPipeline(
    private val context: Context,
    private val compositor: GlCompositor,
    val outputFile: File,
    val segmentIntervalUs: Long = 10_000_000L, // 10 detik
    private val frameRate: Int = 30,
    private val videoBitrate: Int = 4_000_000,
    private val audioBitrate: Int = 128_000,
    private val useBuiltinMic: Boolean = true,
    var sink: EncodedSink? = null,
) {
    private var muxer: SegmentedMp4Muxer? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var audioRecorder: AudioRecorder? = null

    var isRecording: Boolean = false
        private set

    private var startTimeMs = 0L
    private var startTimeNs = 0L
    private val audioBytes = ByteBuffer.allocateDirect(AudioEngineBridge.MAX_PCM_BYTES)
        .order(ByteOrder.nativeOrder())

    fun start() {
        if (isRecording) return
        Log.i(TAG, "Memulai pipeline perekaman MP4 berkala: ${outputFile.absolutePath}")

        val dir = outputFile.parentFile ?: context.filesDir
        val baseName = outputFile.nameWithoutExtension

        val m = SegmentedMp4Muxer(dir, baseName, segmentIntervalUs)
        muxer = m

        val ve = VideoEncoder(
            width = compositor.canvasWidth,
            height = compositor.canvasHeight,
            frameRate = frameRate,
            bitRate = videoBitrate,
        )
        ve.onFormatAvailable = { format: MediaFormat ->
            m.addVideoTrack(format)
            sink?.onVideoFormat(format)
        }
        ve.onSampleAvailable = { buffer: ByteBuffer, info: MediaCodec.BufferInfo ->
            m.writeVideoSample(buffer, info)
            sink?.onVideoSample(buffer, info)
        }
        ve.start()
        videoEncoder = ve

        val startNs = SystemClock.elapsedRealtimeNanos()
        startTimeNs = startNs
        compositor.setEncoderSurface(ve.inputSurface, startNs)

        val ae = AudioEncoder(
            sampleRate = 48000,
            channelCount = 2,
            bitRate = audioBitrate,
        )
        ae.onFormatAvailable = { format: MediaFormat ->
            m.addAudioTrack(format)
            sink?.onAudioFormat(format)
        }
        ae.onSampleAvailable = { buffer: ByteBuffer, info: MediaCodec.BufferInfo ->
            m.writeAudioSample(buffer, info)
            sink?.onAudioSample(buffer, info)
        }
        ae.start()
        audioEncoder = ae

        if (useBuiltinMic) {
            val ar = AudioRecorder(sampleRate = 48000)
            ar.onPcmDataAvailable = { buffer: ByteArray, read: Int, ptsUs: Long ->
                ae.encodePcm(buffer, read, ptsUs)
            }
            ar.start()
            audioRecorder = ar
        }

        startTimeMs = SystemClock.elapsedRealtime()
        isRecording = true
    }

    /** PCM stereo 48 kHz dari AudioEngine. Dipanggil di thread audio; tanpa alokasi. */
    fun feedAudio(pcm: ShortArray, frames: Int, timeNs: Long) {
        val encoder = audioEncoder ?: return
        if (!isRecording) return
        val samples = frames * 2
        if (samples <= 0 || samples > pcm.size) return
        audioBytes.clear()
        audioBytes.asShortBuffer().put(pcm, 0, samples)
        val ptsUs = ((timeNs - startTimeNs) / 1000L).coerceAtLeast(0L)
        encoder.encodeDirect(audioBytes, samples * 2, ptsUs)
    }

    fun requestKeyFrame() {
        videoEncoder?.requestKeyFrame()
    }

    fun setBitrate(bps: Int) {
        videoEncoder?.setBitRate(bps)
    }

    fun getDurationMs(): Long {
        return if (isRecording) SystemClock.elapsedRealtime() - startTimeMs else 0L
    }

    fun stop(): List<File> {
        if (!isRecording) return listOf(outputFile)
        isRecording = false
        Log.i(TAG, "Menghentikan pipeline perekaman MP4...")

        compositor.setEncoderSurface(null)

        audioRecorder?.stop()
        audioRecorder = null

        audioEncoder?.stop()
        audioEncoder = null

        videoEncoder?.stop()
        videoEncoder = null

        val files = muxer?.stop() ?: listOf(outputFile)
        muxer = null

        Log.i(TAG, "Selesai merekam MP4. Segmen tersimpan: ${files.joinToString { it.name }}")
        return files
    }

    companion object {
        private const val TAG = "SP-RecordingPipeline"
    }
}

/** Batas ukuran blok PCM yang diterima pipeline; satu blok audio 20 ms = 3840 byte. */
internal object AudioEngineBridge {
    const val MAX_PCM_BYTES = 16_384
}
