package com.studiopocket.core.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encoder Video Hardware H.264 ([MediaCodec]) yang menerima frame langsung via Surface.
 */
class VideoEncoder(
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,
    val bitRate: Int = 4_000_000, // 4 Mbps
) {
    var onFormatAvailable: ((MediaFormat) -> Unit)? = null
    var onSampleAvailable: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var drainThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (isRunning.get()) return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2 detik
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec

        isRunning.set(true)

        drainThread = Thread({
            drainEncoder()
        }, "SP-VideoEncoderDrain").apply { start() }

        Log.i(TAG, "VideoEncoder H.264 dimulai ($width x $height @ $frameRate fps, $bitRate bps)")
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Format output H.264 berubah: $newFormat")
                    onFormatAvailable?.invoke(newFormat)
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer: ByteBuffer? = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        onSampleAvailable?.invoke(outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            } catch (t: Throwable) {
                if (isRunning.get()) {
                    Log.e(TAG, "Galat saat dequeue buffer video encoder", t)
                }
            }
        }
    }

    fun requestKeyFrame() {
        val codec = mediaCodec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (t: Throwable) {
            Log.w(TAG, "Permintaan keyframe gagal: ${t.message}")
        }
    }

    fun setBitRate(bps: Int) {
        if (bps <= 0) return
        val codec = mediaCodec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            codec.setParameters(params)
        } catch (t: Throwable) {
            Log.w(TAG, "Perubahan bitrate gagal: ${t.message}")
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Menghentikan VideoEncoder...")

        try {
            drainThread?.join(1000)
        } catch (ignored: InterruptedException) {
        }

        try {
            mediaCodec?.signalEndOfInputStream()
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            inputSurface?.release()
            inputSurface = null
            Log.i(TAG, "VideoEncoder dihentikan")
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menghentikan VideoEncoder", t)
        }
    }

    companion object {
        private const val TAG = "SP-VideoEncoder"
    }
}
