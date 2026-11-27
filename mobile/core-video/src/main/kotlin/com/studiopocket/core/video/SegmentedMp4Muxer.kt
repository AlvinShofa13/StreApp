package com.studiopocket.core.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Muxer MP4 berkala (Segmented MP4) untuk ketahanan pemulihan rekaman saat crash/force stop.
 * Menulis file MP4 secara terpisah tiap [segmentIntervalUs] (default 10 detik) tepat pada batas KeyFrame.
 */
class SegmentedMp4Muxer(
    val baseDir: File,
    val baseName: String,
    val segmentIntervalUs: Long = 10_000_000L, // 10 detik
) {
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var currentMuxer: Mp4Muxer? = null
    private var segmentIndex = 1
    private var currentSegmentStartPtsUs = -1L
    private val completedFiles = mutableListOf<File>()

    private var isClosed = false

    @Synchronized
    fun addVideoTrack(format: MediaFormat) {
        if (isClosed) return
        videoFormat = format
        mencobaMulaiSegmenBaru()
    }

    @Synchronized
    fun addAudioTrack(format: MediaFormat) {
        if (isClosed) return
        audioFormat = format
        mencobaMulaiSegmenBaru()
    }

    @Synchronized
    private fun mencobaMulaiSegmenBaru() {
        val vf = videoFormat ?: return
        val af = audioFormat ?: return

        if (currentMuxer == null && !isClosed) {
            val fileName = if (segmentIndex == 1) "$baseName.mp4" else "${baseName}_part$segmentIndex.mp4"
            val file = File(baseDir, fileName)
            Log.i(TAG, "Membuat segmen MP4 baru: ${file.name}")

            val m = Mp4Muxer(file.absolutePath)
            m.addVideoTrack(vf)
            m.addAudioTrack(af)
            currentMuxer = m
            completedFiles.add(file)
        }
    }

    @Synchronized
    fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (isClosed) return
        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        if (currentSegmentStartPtsUs < 0) {
            currentSegmentStartPtsUs = info.presentationTimeUs
        }

        val segmentDurationUs = info.presentationTimeUs - currentSegmentStartPtsUs

        // Rotasi segmen jika sudah mencapai interval dan tiba di I-Frame (KeyFrame)
        if (isKeyFrame && segmentDurationUs >= segmentIntervalUs && currentMuxer != null) {
            rotasiSegmen(info.presentationTimeUs)
        }

        currentMuxer?.writeVideoSample(buffer, info)
    }

    @Synchronized
    fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (isClosed) return
        currentMuxer?.writeAudioSample(buffer, info)
    }

    private fun rotasiSegmen(newPtsUs: Long) {
        Log.i(TAG, "Menutup segmen MP4 part $segmentIndex (durasi: ${ (newPtsUs - currentSegmentStartPtsUs) / 1000 } ms)")
        currentMuxer?.stop()
        currentMuxer = null

        segmentIndex++
        currentSegmentStartPtsUs = newPtsUs
        mencobaMulaiSegmenBaru()
    }

    @Synchronized
    fun stop(): List<File> {
        if (isClosed) return completedFiles
        isClosed = true

        try {
            currentMuxer?.stop()
            currentMuxer = null
            Log.i(TAG, "SegmentedMp4Muxer selesai. Segmen tersimpan: ${completedFiles.joinToString { it.name }}")
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menghentikan SegmentedMp4Muxer", t)
        }

        return completedFiles.toList()
    }

    companion object {
        private const val TAG = "SP-SegmentedMuxer"
    }
}
