package com.studiopocket.core.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Tiga mode monitoring per sumber suara (T2.2). */
enum class MonitorMode {
    /** Tidak didengar, tetap direkam. */
    MATI,

    /** Didengar, tidak masuk rekaman. */
    HANYA_MONITOR,

    /** Didengar dan direkam. */
    MONITOR_DAN_REKAM,
}

/**
 * Mixer PCM 16-bit murni tanpa Android, tanpa alokasi di jalur campur.
 *
 * Satu [Track] adalah satu sumber suara. Pemanggil mengisi [Track.samples] lalu
 * memanggil [mix]; hasil rekam dan hasil monitoring ditulis ke buffer yang sudah ada.
 * Limiter bekerja per blok: bila puncak melewati ambang, seluruh blok diperkecil.
 */
class AudioMixer(
    val channelCount: Int = 2,
    val sampleRate: Int = 48000,
    private val limiterThreshold: Float = 0.95f,
) {
    init {
        require(channelCount > 0)
        require(limiterThreshold in 0.1f..1f)
    }

    inner class Track(
        val id: String,
        val frameCapacity: Int,
        var gain: Float = 1f,
        var muted: Boolean = false,
        var monitorMode: MonitorMode = MonitorMode.MATI,
        /** Dimatikan otomatis untuk mic bawaan saat tanpa headphone guna mencegah feedback (nging). */
        var allowMonitor: Boolean = true,
    ) {
        val samples = ShortArray(frameCapacity * channelCount)
        var frameCount = 0
            private set

        /** Puncak sumber sebelum gain, 0..1, untuk meter UI. */
        var level: Float = 0f
            private set

        fun put(data: ShortArray, frames: Int) {
            val n = min(frames, frameCapacity)
            System.arraycopy(data, 0, samples, 0, n * channelCount)
            frameCount = n
        }

        fun putSilence() {
            frameCount = 0
        }

        internal fun updateLevel() {
            var peak = 0
            val n = frameCount * channelCount
            for (i in 0 until n) {
                val v = abs(samples[i].toInt())
                if (v > peak) peak = v
            }
            level = peak / 32768f
        }

        internal fun hasFrames(): Boolean = frameCount > 0
        internal fun records(): Boolean = !muted && monitorMode != MonitorMode.HANYA_MONITOR
        internal fun monitors(): Boolean = !muted && allowMonitor && monitorMode != MonitorMode.MATI
    }

    private var accumCap = 0
    private var recordAccum = FloatArray(0)
    private var monitorAccum = FloatArray(0)

    private fun ensureCapacity(samples: Int) {
        if (samples <= accumCap) return
        accumCap = samples
        recordAccum = FloatArray(samples)
        monitorAccum = FloatArray(samples)
    }

    /**
     * Campur satu blok.
     *
     * @param frames jumlah frame yang diminta; buffer keluaran harus >= frames * channelCount.
     * @param recordOut hasil untuk rekaman; null bila hanya monitoring.
     * @param monitorOut hasil untuk headphone; null bila monitoring mati.
     * @return true bila ada sumber yang dimonitor sehingga monitorOut berisi data.
     */
    fun mix(
        tracks: List<Track>,
        frames: Int,
        recordOut: ShortArray?,
        monitorOut: ShortArray?,
    ): Boolean {
        val samples = frames * channelCount
        require(frames >= 0)
        require(recordOut == null || recordOut.size >= samples) { "buffer rekam terlalu kecil" }
        require(monitorOut == null || monitorOut.size >= samples) { "buffer monitor terlalu kecil" }
        ensureCapacity(samples)
        java.util.Arrays.fill(recordAccum, 0, samples, 0f)
        java.util.Arrays.fill(monitorAccum, 0, samples, 0f)

        var monitored = false
        for (track in tracks) {
            track.updateLevel()
            if (!track.hasFrames()) continue
            val n = min(track.frameCount, frames) * channelCount
            val gain = track.gain
            val record = track.records()
            val monitor = track.monitors()
            if (monitor) monitored = true
            if (!record && !monitor) continue
            for (i in 0 until n) {
                val v = track.samples[i] / 32768f * gain
                if (record) recordAccum[i] += v
                if (monitor) monitorAccum[i] += v
            }
        }

        if (recordOut != null) {
            val skala = skalaLimiter(recordAccum, samples)
            tulis(recordAccum, recordOut, samples, skala)
        }
        if (monitorOut != null) {
            val skala = skalaLimiter(monitorAccum, samples)
            tulis(monitorAccum, monitorOut, samples, skala)
        }
        return monitored
    }

    private fun skalaLimiter(acc: FloatArray, samples: Int): Float {
        var puncak = 0f
        for (i in 0 until samples) {
            val v = abs(acc[i])
            if (v > puncak) puncak = v
        }
        return if (puncak > limiterThreshold) limiterThreshold / puncak else 1f
    }

    private fun tulis(acc: FloatArray, out: ShortArray, samples: Int, skala: Float) {
        for (i in 0 until samples) {
            val v = (acc[i] * skala * 32767f).roundToInt()
            out[i] = v.coerceIn(-32768, 32767).toShort()
        }
    }
}
