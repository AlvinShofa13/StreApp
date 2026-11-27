package com.studiopocket.core.video

/**
 * Posisi dan ukuran layer dalam piksel kanvas komposisi (misal: kanvas 1280x720).
 * [x] dan [y] adalah posisi sudut kiri atas layer.
 */
data class LayerRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    /**
     * Konversi posisi piksel kanvas ke Normalized Device Coordinates (NDC: -1..1) OpenGL.
     */
    fun toNdc(canvasWidth: Float, canvasHeight: Float): FloatArray {
        val left = (x / canvasWidth) * 2f - 1f
        val top = 1f - (y / canvasHeight) * 2f
        val right = ((x + width) / canvasWidth) * 2f - 1f
        val bottom = 1f - ((y + height) / canvasHeight) * 2f

        // Quad 2D: [X, Y, U, V]
        // Kiri-atas, Kiri-bawah, Kanan-atas, Kanan-bawah
        return floatArrayOf(
            left, top, 0f, 1f,       // Top-Left
            left, bottom, 0f, 0f,    // Bottom-Left
            right, top, 1f, 1f,      // Top-Right
            right, bottom, 1f, 0f,   // Bottom-Right
        )
    }
}
