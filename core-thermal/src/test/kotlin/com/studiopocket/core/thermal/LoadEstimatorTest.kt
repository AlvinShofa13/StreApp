package com.studiopocket.core.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadEstimatorTest {

    @Test
    fun `satu sumber 720p dianggap ringan`() {
        val e = LoadEstimator.estimasi(0, 1, 0, 0, 0, 1280, 30)
        assertEquals(LoadLevel.RINGAN, e.level)
        assertFalse(e.saranHemat)
    }

    @Test
    fun `capture card kamera dan mic 1080p masuk sedang`() {
        val e = LoadEstimator.estimasi(1, 1, 0, 0, 0, 1920, 30)
        assertEquals(LoadLevel.SEDANG, e.level)
    }

    @Test
    fun `kombinasi berat menyarankan preset hemat`() {
        val e = LoadEstimator.estimasi(1, 1, 1, 2, 0, 1280, 30)
        assertEquals(LoadLevel.BERAT, e.level)
        assertTrue(e.saranHemat)
    }

    @Test
    fun `banyak sumber dan 60 fps menjadi maksimum`() {
        val e = LoadEstimator.estimasi(1, 1, 1, 3, 0, 1920, 60)
        assertEquals(LoadLevel.MAKSIMUM, e.level)
        assertTrue(e.saranHemat)
    }
}
