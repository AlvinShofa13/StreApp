package com.studiopocket.core.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun `status sistem dipetakan ke lima tingkat`() {
        assertEquals(ThermalLevel.NORMAL, ThermalPolicy.levelFromStatus(0))
        assertEquals(ThermalLevel.RINGAN, ThermalPolicy.levelFromStatus(1))
        assertEquals(ThermalLevel.SEDANG, ThermalPolicy.levelFromStatus(2))
        assertEquals(ThermalLevel.BERAT, ThermalPolicy.levelFromStatus(3))
        assertEquals(ThermalLevel.KRITIS, ThermalPolicy.levelFromStatus(4))
        assertEquals(ThermalLevel.KRITIS, ThermalPolicy.levelFromStatus(5))
    }

    @Test
    fun `tingkat makin panas menurunkan kualitas bertahap`() {
        val normal = ThermalPolicy.action(ThermalLevel.NORMAL)
        val ringan = ThermalPolicy.action(ThermalLevel.RINGAN)
        val sedang = ThermalPolicy.action(ThermalLevel.SEDANG)
        val berat = ThermalPolicy.action(ThermalLevel.BERAT)
        val kritis = ThermalPolicy.action(ThermalLevel.KRITIS)

        assertTrue(normal.previewFps >= ringan.previewFps)
        assertTrue(ringan.previewFps >= sedang.previewFps)
        assertTrue(sedang.previewScale >= berat.previewScale)
        assertTrue(berat.encoderFps > kritis.encoderFps)
        assertTrue(berat.bitrateScale > kritis.bitrateScale)
        assertFalse(berat.stopRecording)
        assertTrue(kritis.stopRecording)
    }

    @Test
    fun `hanya kritis yang menghentikan rekaman`() {
        ThermalLevel.entries.forEach {
            assertEquals(it == ThermalLevel.KRITIS, ThermalPolicy.action(it).stopRecording)
        }
    }

    @Test
    fun `naik dan turun level terdeteksi`() {
        assertTrue(ThermalPolicy.perluTurun(ThermalLevel.NORMAL, ThermalLevel.SEDANG))
        assertFalse(ThermalPolicy.perluTurun(ThermalLevel.BERAT, ThermalLevel.SEDANG))
        assertTrue(ThermalPolicy.perluNaik(ThermalLevel.BERAT, ThermalLevel.SEDANG))
    }
}
