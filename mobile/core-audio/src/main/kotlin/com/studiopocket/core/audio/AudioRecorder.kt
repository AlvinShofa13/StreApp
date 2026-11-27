package com.studiopocket.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Perekam mikrofon berprioritas tinggi menggunakan [AudioRecord].
 */
class AudioRecorder(
    val sampleRate: Int = 48000,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
) {
    var onPcmDataAvailable: ((pcmBuffer: ByteArray, bytesRead: Int, ptsUs: Long) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get()) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord gagal terinisialisasi")
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()
        isRunning.set(true)

        recordThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(2048)
            val startTimeUs = System.nanoTime() / 1000L

            while (isRunning.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val currentPtsUs = (System.nanoTime() / 1000L) - startTimeUs
                    onPcmDataAvailable?.invoke(buffer, read, currentPtsUs)
                }
            }
        }, "SP-AudioRecordThread").apply { start() }

        Log.i(TAG, "AudioRecorder dimulai ($sampleRate Hz, stereo)")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Menghentikan AudioRecorder...")

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordThread?.join(1000)
            Log.i(TAG, "AudioRecorder dihentikan")
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal menghentikan AudioRecorder", t)
        }
    }

    companion object {
        private const val TAG = "SP-AudioRecorder"
    }
}
