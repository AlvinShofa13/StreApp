package com.studiopocket.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encoder AAC ([MediaCodec]) yang meng-encode sampel PCM audio ke format AAC-LC.
 */
class AudioEncoder(
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val bitRate: Int = 128_000, // 128 kbps
) {
    var onFormatAvailable: ((MediaFormat) -> Unit)? = null
    var onSampleAvailable: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    private var mediaCodec: MediaCodec? = null
    private var drainThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (isRunning.get()) return

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        mediaCodec = codec

        isRunning.set(true)

        drainThread = Thread({
            drainEncoder()
        }, "SP-AudioEncoderDrain").apply { start() }

        Log.i(TAG, "AudioEncoder AAC dimulai ($sampleRate Hz, $channelCount channel, $bitRate bps)")
    }

    fun encodePcm(pcmBuffer: ByteArray, bytesRead: Int, ptsUs: Long) {
        val codec = mediaCodec ?: return
        if (!isRunning.get() || bytesRead <= 0) return

        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(pcmBuffer, 0, bytesRead)
                    codec.queueInputBuffer(inputIndex, 0, bytesRead, ptsUs, 0)
                }
            }
        } catch (t: Throwable) {
            if (isRunning.get()) {
                Log.e(TAG, "Galat saat queueInputBuffer audio", t)
            }
        }
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Format output AAC berubah: $newFormat")
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
                    Log.e(TAG, "Galat saat dequeue buffer audio encoder", t)
                }
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Menghentikan AudioEncoder...")

        try {
            drainThread?.join(1000)
        } catch (ignored: InterruptedException) {
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            Log.i(TAG, "AudioEncoder dihentikan")
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menghentikan AudioEncoder", t)
        }
    }

    companion object {
        private const val TAG = "SP-AudioEncoder"
    }
}
