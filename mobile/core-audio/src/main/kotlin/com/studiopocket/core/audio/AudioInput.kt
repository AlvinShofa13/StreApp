package com.studiopocket.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Satu input mikrofon/USB/audio internal lewat [AudioRecord].
 * Frame ditulis ke [PcmRing] stereo; konversi mono ke stereo dilakukan di sini.
 */
class AudioInput(
    val id: String,
    private val sampleRate: Int = 48000,
    private val device: AudioDeviceInfo? = null,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val playbackConfig: AudioPlaybackCaptureConfiguration? = null,
) {
    val ring = PcmRing(channels = 2, frameCapacity = sampleRate)

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var routedDeviceName: String? = null
        private set

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        // Coba stereo terlebih dahulu; beberapa capture card USB hanya mendukung mono
        val candidates = listOf(
            2 to AudioFormat.CHANNEL_IN_STEREO,
            1 to AudioFormat.CHANNEL_IN_MONO,
        )
        var rec: AudioRecord? = null
        var inChannels = 2

        for ((channels, channelMask) in candidates) {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val built = try {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
                val builder = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer * 2)
                if (playbackConfig != null) {
                    builder.setAudioPlaybackCaptureConfig(playbackConfig)
                } else {
                    builder.setAudioSource(audioSource)
                }
                val r = builder.build()
                if (device != null && !r.setPreferredDevice(device)) {
                    Log.w(TAG, "Perangkat audio pilihan tidak dapat dipakai, memakai bawaan")
                }
                r
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Gagal membuat AudioRecord ($channels ch): ${t.message}")
                null
            }
            if (built != null && built.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    built.startRecording()
                    rec = built
                    inChannels = channels
                    break
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                    built.release()
                    Log.w(TAG, "Gagal startRecording ($channels ch): ${t.message}")
                }
            } else {
                built?.release()
            }
        }

        val activeRecord = rec
        if (activeRecord == null) {
            if (lastError == null) lastError = "AudioRecord tidak siap"
            Log.e(TAG, "Gagal membuka input audio $id: $lastError")
            return false
        }

        record = activeRecord
        routedDeviceName = activeRecord.routedDevice?.productName?.toString()
        running.set(true)
        val chunkFrames = 1024
        val readBuffer = ShortArray(chunkFrames * inChannels)
        val stereoBuffer = ShortArray(chunkFrames * 2)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running.get()) {
                val read = try {
                    activeRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                } catch (t: Throwable) {
                    if (running.get()) lastError = t.message ?: "read gagal"
                    break
                }
                if (read < 0) {
                    if (read != AudioRecord.ERROR_INVALID_OPERATION && running.get()) {
                        lastError = "read error $read"
                    }
                    break
                }
                if (read == 0) continue
                val frames = read / inChannels
                if (inChannels == 2) {
                    System.arraycopy(readBuffer, 0, stereoBuffer, 0, frames * 2)
                } else {
                    for (i in 0 until frames) {
                        val v = readBuffer[i]
                        stereoBuffer[i * 2] = v
                        stereoBuffer[i * 2 + 1] = v
                    }
                }
                ring.write(stereoBuffer, frames)
            }
        }, "SP-AudioInput-$id").apply { start() }
        Log.i(TAG, "Audio input $id dimulai (${if (inChannels == 2) "stereo" else "mono"})")
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            record?.release()
        } catch (_: Throwable) {
        }
        record = null
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
        ring.clear()
    }

    companion object {
        private const val TAG = "SP-AudioInput"
    }
}

/** Input audio dari Camera Node: node mengirim PCM stereo 16-bit 48 kHz. */
class RemoteAudioInput(val id: String, sampleRate: Int = 48000) {
    val ring = PcmRing(channels = 2, frameCapacity = sampleRate)

    fun push(samples: ShortArray, frames: Int) {
        ring.write(samples, frames)
    }

    fun stop() {
        ring.clear()
    }
}
