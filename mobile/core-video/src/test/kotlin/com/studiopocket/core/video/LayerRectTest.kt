package com.studiopocket.core.video

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerRectTest {

    @Test
    fun testFullScreenNdc() {
        val rect = LayerRect(0f, 0f, 1280f, 720f)
        val ndc = rect.toNdc(1280f, 720f)

        // Top-Left: (-1, 1)
        assertEquals(-1.0f, ndc[0], 0.001f)
        assertEquals(1.0f, ndc[1], 0.001f)

        // Bottom-Right: (1, -1)
        assertEquals(1.0f, ndc[12], 0.001f)
        assertEquals(-1.0f, ndc[13], 0.001f)
    }

    @Test
    fun testCenterHalfNdc() {
        val rect = LayerRect(320f, 180f, 640f, 360f)
        val ndc = rect.toNdc(1280f, 720f)

        // Top-Left: (-0.5, 0.5)
        assertEquals(-0.5f, ndc[0], 0.001f)
        assertEquals(0.5f, ndc[1], 0.001f)

        // Bottom-Right: (0.5, -0.5)
        assertEquals(0.5f, ndc[12], 0.001f)
        assertEquals(-0.5f, ndc[13], 0.001f)
    }
}
