package com.studiopocket.core.video

import org.junit.Assert.assertEquals
import org.junit.Test

class GlCompositorAspectTest {

    @Test
    fun testCamera2PortraitStMatrixSwappedNoUserRotation() {
        // Sensor Camera2 1280x720 dengan stMatrix sudah terputar 90 derajat -> hasil efektif 720x1280 (Potret)
        val (w, h) = GlCompositor.effectiveSourceSize(
            sourceWidth = 1280,
            sourceHeight = 720,
            stSwapped = true,
            rotationDegrees = 0,
        )
        assertEquals(720f, w, 0.01f)
        assertEquals(1280f, h, 0.01f)
    }

    @Test
    fun testCamera2LandscapeUserRotationCancelsStSwap() {
        // Sensor Camera2 1280x720 + user pilih Lanskap (90/270 derajat) -> hasil efektif 1280x720 (Lanskap)
        val (w90, h90) = GlCompositor.effectiveSourceSize(1280, 720, stSwapped = true, rotationDegrees = 90)
        assertEquals(1280f, w90, 0.01f)
        assertEquals(720f, h90, 0.01f)

        val (w270, h270) = GlCompositor.effectiveSourceSize(1280, 720, stSwapped = true, rotationDegrees = 270)
        assertEquals(1280f, w270, 0.01f)
        assertEquals(720f, h270, 0.01f)
    }

    @Test
    fun testRemoteDecoderPortraitAndLandscapePreserved() {
        // Decoder H.264 (stSwapped = false) menerima 720x1280 dari HP B Potret
        val (wp, hp) = GlCompositor.effectiveSourceSize(720, 1280, stSwapped = false, rotationDegrees = 0)
        assertEquals(720f, wp, 0.01f)
        assertEquals(1280f, hp, 0.01f)

        // Decoder H.264 menerima 1280x720 dari HP B Lanskap
        val (wl, hl) = GlCompositor.effectiveSourceSize(1280, 720, stSwapped = false, rotationDegrees = 0)
        assertEquals(1280f, wl, 0.01f)
        assertEquals(720f, hl, 0.01f)
    }
}
