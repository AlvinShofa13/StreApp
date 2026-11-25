package com.studiopocket.core.video

import android.graphics.SurfaceTexture

enum class LayerType {
    OES_TEXTURE,
    COLOR_BARS,
}

/**
 * Representasi layer input dalam kompositor GL.
 */
class CompositorLayer(
    val id: String,
    val type: LayerType,
    var rect: LayerRect,
    var zIndex: Int = 0,
    var isVisible: Boolean = true,
    var textureId: Int = 0,
    var surfaceTexture: SurfaceTexture? = null,
    var sourceWidth: Int = 1280,
    var sourceHeight: Int = 720,
    var rotationDegrees: Int = 0,
    var isMirrored: Boolean = false,
) {
    val stMatrix = FloatArray(16)

    init {
        setIdentityMatrix(stMatrix)
    }

    private fun setIdentityMatrix(sm: FloatArray) {
        for (i in sm.indices) sm[i] = 0f
        sm[0] = 1f
        sm[5] = 1f
        sm[10] = 1f
        sm[15] = 1f
    }
}
