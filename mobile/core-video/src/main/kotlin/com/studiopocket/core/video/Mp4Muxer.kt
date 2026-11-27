package com.studiopocket.core.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * Pengelola [MediaMuxer] untuk menggabungkan track video (H.264) dan track audio (AAC) ke file MP4.
 */
class Mp4Muxer(
    outputPath: String,
) {
    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private var isClosed = false

    @Synchronized
    fun addVideoTrack(format: MediaFormat) {
        if (isMuxerStarted || isClosed) return
        videoTrackIndex = muxer.addTrack(format)
        Log.i(TAG, "Track Video H.264 ditambahkan (index=$videoTrackIndex)")
        mencobaMulaiMuxer()
    }

    @Synchronized
    fun addAudioTrack(format: MediaFormat) {
        if (isMuxerStarted || isClosed) return
        audioTrackIndex = muxer.addTrack(format)
        Log.i(TAG, "Track Audio AAC ditambahkan (index=$audioTrackIndex)")
        mencobaMulaiMuxer()
    }

    @Synchronized
    private fun mencobaMulaiMuxer() {
        if (!isMuxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer.start()
            isMuxerStarted = true
            Log.i(TAG, "MediaMuxer MP4 berhasil dimulai")
        }
    }

    @Synchronized
    fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!isMuxerStarted || isClosed || videoTrackIndex < 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (info.size <= 0) return

        try {
            muxer.writeSampleData(videoTrackIndex, buffer, info)
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menulis sample video MP4", t)
        }
    }

    @Synchronized
    fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!isMuxerStarted || isClosed || audioTrackIndex < 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (info.size <= 0) return

        try {
            muxer.writeSampleData(audioTrackIndex, buffer, info)
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menulis sample audio MP4", t)
        }
    }

    @Synchronized
    fun stop() {
        if (isClosed) return
        isClosed = true
        try {
            if (isMuxerStarted) {
                muxer.stop()
            }
            muxer.release()
            Log.i(TAG, "MediaMuxer MP4 dihentikan")
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menghentikan MediaMuxer MP4", t)
        }
    }

    companion object {
        private const val TAG = "SP-Mp4Muxer"
    }
}
