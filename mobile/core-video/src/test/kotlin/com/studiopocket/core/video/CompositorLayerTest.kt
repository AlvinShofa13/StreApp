package com.studiopocket.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorLayerTest {

    @Test
    fun testLayerZIndexSorting() {
        val layerBackground = CompositorLayer(
            id = "bg",
            type = LayerType.COLOR_BARS,
            rect = LayerRect(0f, 0f, 1280f, 720f),
            zIndex = 0,
        )
        val layerPip = CompositorLayer(
            id = "pip",
            type = LayerType.OES_TEXTURE,
            rect = LayerRect(800f, 400f, 400f, 250f),
            zIndex = 1,
        )
        val layerOverlay = CompositorLayer(
            id = "overlay",
            type = LayerType.COLOR_BARS,
            rect = LayerRect(50f, 50f, 300f, 150f),
            zIndex = 2,
        )

        val layers = listOf(layerOverlay, layerBackground, layerPip)
        val sorted = layers.sortedBy { it.zIndex }

        assertEquals("bg", sorted[0].id)
        assertEquals("pip", sorted[1].id)
        assertEquals("overlay", sorted[2].id)
    }

    @Test
    fun testLayerVisibilityToggle() {
        val layer = CompositorLayer(
            id = "test",
            type = LayerType.COLOR_BARS,
            rect = LayerRect(0f, 0f, 100f, 100f),
            isVisible = true,
        )
        assertTrue(layer.isVisible)

        layer.isVisible = false
        assertFalse(layer.isVisible)
    }
}
