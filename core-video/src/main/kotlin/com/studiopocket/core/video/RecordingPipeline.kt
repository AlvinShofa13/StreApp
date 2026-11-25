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

/**
 * Pipeline perekaman gabungan video (H.264) dan audio (AAC) berkala (Segmented MP4).
 * Menjamin pemulihan file rekaman jika aplikasi dimatikan paksa (crash / force stop).
 */
class RecordingPipeline(
    private val context: Context,
    private val compositor: GlCompositor,
    val outputFile: File,
    val segmentIntervalUs: Long = 10_000_000L, // 10 detik
) {
    private var muxer: SegmentedMp4Muxer? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var audioRecorder: AudioRecorder? = null

    var isRecording: Boolean = false
        private set

    private var startTimeMs = 0L

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
            frameRate = 30,
            bitRate = 4_000_000,
        )
        ve.onFormatAvailable = { format: MediaFormat -> m.addVideoTrack(format) }
        ve.onSampleAvailable = { buffer: ByteBuffer, info: MediaCodec.BufferInfo -> m.writeVideoSample(buffer, info) }
        ve.start()
        videoEncoder = ve

        compositor.setEncoderSurface(ve.inputSurface)

        val ae = AudioEncoder(
            sampleRate = 48000,
            channelCount = 2,
            bitRate = 128_000,
        )
        ae.onFormatAvailable = { format: MediaFormat -> m.addAudioTrack(format) }
        ae.onSampleAvailable = { buffer: ByteBuffer, info: MediaCodec.BufferInfo -> m.writeAudioSample(buffer, info) }
        ae.start()
        audioEncoder = ae

        val ar = AudioRecorder(sampleRate = 48000)
        ar.onPcmDataAvailable = { buffer: ByteArray, read: Int, ptsUs: Long ->
            ae.encodePcm(buffer, read, ptsUs)
        }
        ar.start()
        audioRecorder = ar

        startTimeMs = SystemClock.elapsedRealtime()
        isRecording = true
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
