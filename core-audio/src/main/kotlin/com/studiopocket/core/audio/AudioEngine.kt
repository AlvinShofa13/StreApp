package com.studiopocket.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.max

/**
 * Mesin audio: beberapa input (mic, USB, audio internal, Camera Node) dicampur ke satu
 * blok 20 ms di thread berprioritas audio, lalu diberikan ke encoder dan monitoring.
 *
 * Tidak ada alokasi di loop campur: buffer, ring, dan daftar track disiapkan di luar loop.
 */
class AudioEngine(context: Context) {
    data class ChannelInfo(
        val id: String,
        val label: String,
        val kind: AudioInputKind,
        val deviceId: Int?,
        val gain: Float,
        val muted: Boolean,
        val monitor: MonitorMode,
    )

    private class Channel(
        val id: String,
        var label: String,
        val kind: AudioInputKind,
        val deviceId: Int?,
        val track: AudioMixer.Track,
        var input: AudioInput?,
        var remote: RemoteAudioInput?,
        var followDefault: Boolean,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mixer = AudioMixer(channelCount = 2, sampleRate = SAMPLE_RATE)
    private val channels = ArrayList<Channel>()
    private val lock = Any()
    private var channelCount = 0

    @Volatile
    private var channelArray: Array<Channel> = emptyArray()

    @Volatile
    private var trackArray: Array<AudioMixer.Track> = emptyArray()

    @Volatile
    private var trackList: List<AudioMixer.Track> = emptyList()

    @Volatile
    private var running = false

    /** Dipanggil di thread audio; buffer dipakai ulang, konsumsi harus sinkron. */
    @Volatile
    var onPcm: ((pcm: ShortArray, frames: Int, timeNs: Long) -> Unit)? = null

    /** (id sumber, pesan galat) */
    var onError: ((String, String) -> Unit)? = null

    /** Perubahan perangkat audio; argumen = "headset" atau "speaker". */
    var onDeviceChanged: ((String) -> Unit)? = null

    private var mixThread: Thread? = null
    private var monitorTrack: AudioTrack? = null
    private var monitorPlaying = false

    private val recordBuffer = ShortArray(BLOCK_FRAMES * 2)
    private val monitorBuffer = ShortArray(BLOCK_FRAMES * 2)
    private val scratch = ShortArray(BLOCK_FRAMES * 2)
    private var defaultMicLabel = "Mic"

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            perangkatBerubah()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            perangkatBerubah()
        }
    }

    fun start() {
        if (running) return
        running = true
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        mixThread = Thread({ loopCampur() }, "SP-AudioMix").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "AudioEngine dimulai ($SAMPLE_RATE Hz)")
    }

    fun stop() {
        running = false
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Throwable) {
        }
        mixThread?.join(1000)
        mixThread = null
        lepasMonitor()
        synchronized(lock) {
            for (c in channels) {
                c.input?.stop()
                c.remote?.stop()
            }
            channels.clear()
            segarkanSnapshot()
        }
    }

    /** Tambah input mic dari perangkat tertentu; null = perangkat bawaan sistem. */
    fun addMic(device: AudioDeviceInfo?, label: String, followDefault: Boolean = false): String? {
        if (!hasRecordPermission()) {
            onError?.invoke(label, "Izin mikrofon belum diberikan")
            return null
        }
        synchronized(lock) {
            if (device != null) {
                channels.firstOrNull { it.deviceId == device.id && it.kind != AudioInputKind.REMOTE }?.let {
                    return it.id
                }
            }
            val id = "mic-${++channelCount}"
            val input = AudioInput(
                id = id,
                sampleRate = SAMPLE_RATE,
                device = device,
                audioSource = android.media.MediaRecorder.AudioSource.MIC,
            )
            if (!input.start()) {
                onError?.invoke(label, input.lastError ?: "Gagal membuka mikrofon")
                return null
            }
            val kind = device?.let { AudioDeviceCatalog.kindOf(it.type) } ?: AudioInputKind.MIC
            val track = mixer.Track(id, frameCapacity = BLOCK_FRAMES * 4)
            channels.add(
                Channel(
                    id = id,
                    label = label,
                    kind = kind,
                    deviceId = device?.id,
                    track = track,
                    input = input,
                    remote = null,
                    followDefault = followDefault,
                ),
            )
            segarkanSnapshot()
            Log.i(TAG, "Input $label ($kind) ditambahkan")
            return id
        }
    }

    /** Pilih perangkat mic terbaik dan ikuti perubahan colokan headset/USB. */
    fun ensureDefaultMic(label: String): String? {
        defaultMicLabel = label
        val device = AudioDeviceCatalog.bestMic(AudioDeviceCatalog.inputDevices(audioManager))
        synchronized(lock) {
            val existing = channels.firstOrNull { it.followDefault }
            if (existing != null) {
                if (existing.deviceId == device?.id) return existing.id
                channels.remove(existing)
                existing.input?.stop()
                segarkanSnapshot()
            }
        }
        return addMic(device, label, followDefault = true)
    }

    fun addInputDevice(device: AudioDeviceInfo, label: String): String? =
        addMic(device, label, followDefault = false)

    fun inputDevices(): List<AudioDeviceInfo> = AudioDeviceCatalog.inputDevices(audioManager)

    fun outputDevices(): List<AudioDeviceInfo> = AudioDeviceCatalog.outputDevices(audioManager)

    /** Audio internal (Android 10+) lewat MediaProjection. */
    fun addPlaybackCapture(projection: MediaProjection, label: String): String? {
        if (!hasRecordPermission()) return null
        synchronized(lock) {
            if (channels.any { it.kind == AudioInputKind.INTERNAL }) {
                return channels.first { it.kind == AudioInputKind.INTERNAL }.id
            }
            val config = try {
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            } catch (t: Throwable) {
                onError?.invoke(label, t.message ?: "Audio internal tidak tersedia")
                return null
            }
            val id = "internal-${++channelCount}"
            val input = AudioInput(id = id, sampleRate = SAMPLE_RATE, playbackConfig = config)
            if (!input.start()) {
                onError?.invoke(label, input.lastError ?: "Audio internal tidak dapat direkam")
                return null
            }
            val track = mixer.Track(id, frameCapacity = BLOCK_FRAMES * 4)
            channels.add(
                Channel(
                    id = id,
                    label = label,
                    kind = AudioInputKind.INTERNAL,
                    deviceId = null,
                    track = track,
                    input = input,
                    remote = null,
                    followDefault = false,
                ),
            )
            segarkanSnapshot()
            return id
        }
    }

    /** Saluran audio dari Camera Node. Node mengirim PCM stereo 48 kHz. */
    fun addRemoteInput(label: String): RemoteAudioInput? {
        synchronized(lock) {
            val id = "remote-${++channelCount}"
            val remote = RemoteAudioInput(id, SAMPLE_RATE)
            val track = mixer.Track(id, frameCapacity = BLOCK_FRAMES * 4)
            channels.add(
                Channel(
                    id = id,
                    label = label,
                    kind = AudioInputKind.REMOTE,
                    deviceId = null,
                    track = track,
                    input = null,
                    remote = remote,
                    followDefault = false,
                ),
            )
            segarkanSnapshot()
            return remote
        }
    }

    fun removeChannel(id: String) {
        synchronized(lock) {
            val c = channels.firstOrNull { it.id == id } ?: return
            channels.remove(c)
            c.input?.stop()
            c.remote?.stop()
            segarkanSnapshot()
        }
    }

    fun setGain(id: String, gain: Float) {
        synchronized(lock) {
            channels.firstOrNull { it.id == id }?.track?.gain = gain.coerceIn(0f, 2f)
        }
    }

    fun setMuted(id: String, muted: Boolean) {
        synchronized(lock) {
            channels.firstOrNull { it.id == id }?.track?.muted = muted
        }
    }

    fun setMonitor(id: String, mode: MonitorMode) {
        synchronized(lock) {
            channels.firstOrNull { it.id == id }?.track?.monitorMode = mode
        }
    }

    fun channels(): List<ChannelInfo> = synchronized(lock) {
        channels.map {
            ChannelInfo(
                id = it.id,
                label = it.label,
                kind = it.kind,
                deviceId = it.deviceId,
                gain = it.track.gain,
                muted = it.track.muted,
                monitor = it.track.monitorMode,
            )
        }
    }

    fun levels(): Map<String, Float> = synchronized(lock) {
        channels.associate { it.id to it.track.level }
    }

    fun hasHeadphones(): Boolean = AudioDeviceCatalog.hasHeadphones(audioManager)

    fun anyMonitoring(): Boolean = synchronized(lock) {
        channels.any { it.track.monitors() }
    }

    private fun perangkatBerubah() {
        Thread {
            val headphones = hasHeadphones()
            synchronized(lock) {
                val best = AudioDeviceCatalog.bestMic(AudioDeviceCatalog.inputDevices(audioManager))
                val follow = channels.firstOrNull { it.followDefault }
                if (follow != null && follow.deviceId != best?.id) {
                    channels.remove(follow)
                    follow.input?.stop()
                    segarkanSnapshot()
                    addMic(best, defaultMicLabel, followDefault = true)
                }
                val removedIds = AudioDeviceCatalog.inputDevices(audioManager).map { it.id }.toSet()
                val hilang = channels.filter {
                    it.deviceId != null && it.deviceId !in removedIds && it.kind != AudioInputKind.INTERNAL
                }
                for (c in hilang) {
                    channels.remove(c)
                    c.input?.stop()
                }
                if (hilang.isNotEmpty()) segarkanSnapshot()
            }
            onDeviceChanged?.invoke(if (headphones) "headset" else "speaker")
        }.start()
    }

    private fun segarkanSnapshot() {
        channelArray = channels.toTypedArray()
        trackArray = channelArray.map { it.track }.toTypedArray()
        trackList = trackArray.asList()
    }

    private fun loopCampur() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val periodNs = BLOCK_FRAMES * 1_000_000_000L / SAMPLE_RATE
        var next = android.os.SystemClock.elapsedRealtimeNanos()
        var monitorLastWarning = 0L
        while (running) {
            val arr = channelArray
            for (c in arr) {
                val n = c.input?.ring?.read(scratch, BLOCK_FRAMES)
                    ?: c.remote?.ring?.read(scratch, BLOCK_FRAMES)
                    ?: 0
                if (n > 0) c.track.put(scratch, n) else c.track.putSilence()
            }

            var needMonitor = false
            for (t in trackArray) {
                if (t.monitors()) {
                    needMonitor = true
                    break
                }
            }
            mixer.mix(
                tracks = trackList,
                frames = BLOCK_FRAMES,
                recordOut = recordBuffer,
                monitorOut = if (needMonitor) monitorBuffer else null,
            )

            val now = android.os.SystemClock.elapsedRealtimeNanos()
            onPcm?.invoke(recordBuffer, BLOCK_FRAMES, now)
            if (needMonitor) tulisMonitor()

            next += periodNs
            val sisa = next - android.os.SystemClock.elapsedRealtimeNanos()
            if (sisa > 0) {
                try {
                    Thread.sleep(sisa / 1_000_000L, (sisa % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    break
                }
            } else if (sisa < -200_000_000L) {
                next = android.os.SystemClock.elapsedRealtimeNanos()
                if (now - monitorLastWarning > 5_000_000_000L) {
                    Log.w(TAG, "Thread audio tertinggal, jam diselaraskan")
                    monitorLastWarning = now
                }
            }
        }
    }

    private fun tulisMonitor() {
        val track = monitorTrack ?: buatMonitorTrack() ?: return
        if (!monitorPlaying) {
            try {
                track.play()
                monitorPlaying = true
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal memulai monitoring", t)
                lepasMonitor()
                return
            }
        }
        val written = track.write(monitorBuffer, 0, monitorBuffer.size, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            Log.w(TAG, "Monitoring gagal menulis: $written")
            lepasMonitor()
        }
    }

    private fun buatMonitorTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer * 3, 8192))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            monitorTrack = track
            track
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal membuat AudioTrack monitoring", t)
            null
        }
    }

    private fun lepasMonitor() {
        try {
            monitorTrack?.stop()
            monitorTrack?.release()
        } catch (_: Throwable) {
        }
        monitorTrack = null
        monitorPlaying = false
    }

    private fun hasRecordPermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        const val SAMPLE_RATE = 48000
        const val BLOCK_FRAMES = 960
        private const val TAG = "SP-AudioEngine"
    }
}
