package com.studiopocket.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverlayLogicTest {

    @Test
    fun `parse proc stat jam tikur`() {
        val line = "1234 (com.studiopocket.app) S 1 1234 1234 0 -1 4194560 100 0 0 0 100 50 0 0 0 0"
        assertEquals(150L, CpuTicker.jiffiesDariProcStat(line))
    }

    @Test
    fun `parse baris rusak tidak melempar`() {
        assertNull(CpuTicker.jiffiesDariProcStat(""))
        assertNull(CpuTicker.jiffiesDariProcStat("abc"))
        assertNull(CpuTicker.jiffiesDariProcStat("1 (x) S hanya-11-field"))
    }

    @Test
    fun `persen cpu delta jiffies`() {
        // 100 jiffies dalam 5 detik pada 100 tick/detik = 20%
        assertEquals(20.0, CpuTicker.persen(0, 100, 5000), 0.001)
        // satu core penuh selama interval: 500 tick / (100 tick x 5 d) = 100%
        assertEquals(100.0, CpuTicker.persen(0, 500, 5000), 0.001)
        assertEquals(0.0, CpuTicker.persen(0, 100, 0), 0.0)
    }

    @Test
    fun `baris log berformat tetap`() {
        val baris = OverlayFormat.baris(
            sessionMs = 300_000, // 5 menit
            cpuPercent = 12.34,
            memoryMb = 184,
            batteryTempC = 34.5,
            thermalStatus = "LIGHT",
            samples = listOf(
                DebugStats.Sample("uvc", 29.83, 29.83, 3),
                DebugStats.Sample("kamera", 30.0, 29.9, 0),
            ),
        )
        assertEquals(
            "t=00:05:00 cpu=12.3% mem=184MB temp=34.5C thermal=LIGHT srcs=2" +
                " | uvc in=29.8 out=29.8 drop=3 | kamera in=30.0 out=29.9 drop=0",
            baris,
        )
    }

    @Test
    fun `baris tanpa sumber dan tanpa suhu`() {
        val baris = OverlayFormat.baris(0, 0.0, 0, null, "NONE", emptyList())
        assertEquals("t=00:00:00 cpu=0.0% mem=0MB temp=? thermal=NONE srcs=0", baris)
    }

    @Test
    fun `debug stats fps dan reset`() {
        val s = DebugStats()
        s.registerSource("uvc")
        s.registerSource("uvc") // dobel tidak membuat duplikat
        s.noteFrameIn("uvc")
        s.noteFrameOut("uvc")
        s.noteFrameOut("uvc")
        s.noteDrop("uvc")
        val sampel = s.sample(1000)
        assertEquals(1, sampel.size)
        assertEquals(DebugStats.Sample("uvc", 1.0, 2.0, 1), sampel[0])
        // setelah sampel, pencacah kosong lagi; drop tidak reset
        assertTrue(s.sample(1000)[0].let { it.inFps == 0.0 && it.outFps == 0.0 && it.drops == 1L })
        // sumber tidak terdaftar diabaikan
        s.noteFrameIn("tak-ada")
        assertEquals(0.0, s.sample(1000).sumOf { it.inFps }, 0.0)
    }
}
