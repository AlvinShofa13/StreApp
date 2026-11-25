package com.studiopocket.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixerTest {
    private val frames = 4
    private val samples = frames * 2

    private fun isi(nilai: Int): ShortArray = ShortArray(samples).apply { fill(nilai.toShort()) }

    @Test
    fun `dua sumber dijumlahkan`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8).apply { put(isi(1000), frames) }
        val b = mixer.Track("b", 8).apply { put(isi(2000), frames) }
        val out = ShortArray(samples)
        mixer.mix(listOf(a, b), frames, out, null)
        assertEquals(3000, out[0].toInt())
        assertEquals(3000, out[samples - 1].toInt())
    }

    @Test
    fun `mute mengeluarkan sumber dari rekaman`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8).apply { put(isi(1000), frames) }
        val b = mixer.Track("b", 8).apply {
            put(isi(2000), frames)
            muted = true
        }
        val out = ShortArray(samples)
        mixer.mix(listOf(a, b), frames, out, null)
        assertEquals(1000, out[0].toInt())
    }

    @Test
    fun `hanya monitor tidak masuk rekaman`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8).apply {
            put(isi(1000), frames)
            monitorMode = MonitorMode.HANYA_MONITOR
        }
        val rec = ShortArray(samples)
        val mon = ShortArray(samples)
        val monitored = mixer.mix(listOf(a), frames, rec, mon)
        assertTrue(monitored)
        assertEquals(0, rec[0].toInt())
        assertEquals(1000, mon[0].toInt())
    }

    @Test
    fun `mati tidak masuk monitor tetapi tetap direkam`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8).apply {
            put(isi(-1000), frames)
            monitorMode = MonitorMode.MATI
        }
        val rec = ShortArray(samples)
        val mon = ShortArray(samples)
        val monitored = mixer.mix(listOf(a), frames, rec, mon)
        assertTrue(!monitored)
        assertEquals(-1000, rec[0].toInt())
        assertEquals(0, mon[0].toInt())
    }

    @Test
    fun `gain mengatur kekuatan sumber`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8).apply {
            put(isi(1000), frames)
            gain = 0.5f
        }
        val out = ShortArray(samples)
        mixer.mix(listOf(a), frames, out, null)
        assertEquals(500, out[0].toInt())
    }

    @Test
    fun `limiter menjaga puncak di bawah ambang`() {
        val mixer = AudioMixer(limiterThreshold = 0.5f)
        val a = mixer.Track("a", 8).apply { put(isi(30000), frames) }
        val b = mixer.Track("b", 8).apply { put(isi(30000), frames) }
        val out = ShortArray(samples)
        mixer.mix(listOf(a, b), frames, out, null)
        assertTrue(out.maxOf { kotlin.math.abs(it.toInt()) } <= 16384)
    }

    @Test
    fun `track tanpa frame menjadi senyap`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8)
        val out = ShortArray(samples) { 123 }
        mixer.mix(listOf(a), frames, out, null)
        assertEquals(0, out[0].toInt())
    }

    @Test
    fun `meter mengikuti puncak sumber`() {
        val mixer = AudioMixer()
        val a = mixer.Track("a", 8).apply { put(isi(-16384), frames) }
        mixer.mix(listOf(a), frames, ShortArray(samples), null)
        assertEquals(0.5f, a.level, 0.001f)
    }

    @Test
    fun `anti feedback memblokir monitor mic sambil tetap merekam dan memonitor usb`() {
        val mixer = AudioMixer()
        val mic = mixer.Track("mic", 8).apply {
            put(isi(2000), frames)
            monitorMode = MonitorMode.MONITOR_DAN_REKAM
            allowMonitor = false // speaker aktif, cegah bunyi nging
        }
        val usb = mixer.Track("usb", 8).apply {
            put(isi(3000), frames)
            monitorMode = MonitorMode.MONITOR_DAN_REKAM
            allowMonitor = true // capture card aman dimonitor
        }
        val rec = ShortArray(samples)
        val mon = ShortArray(samples)
        val monitored = mixer.mix(listOf(mic, usb), frames, rec, mon)
        assertTrue(monitored)
        assertEquals(5000, rec[0].toInt()) // keduanya tetap terekam
        assertEquals(3000, mon[0].toInt()) // hanya USB yang keluar ke speaker
    }
}
