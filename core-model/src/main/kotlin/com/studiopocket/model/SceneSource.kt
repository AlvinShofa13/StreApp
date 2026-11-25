package com.studiopocket.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { UVC, CAMERA, SCREEN, REMOTE, TEST, IMAGE, TEXT }

/** Bentuk potongan layer video. */
@Serializable
enum class LayerShape { KOTAK, SUDUT_MEMBULAT, BULAT }

/** Posisi dan ukuran dalam koordinat ternormalisasi 0..1 terhadap kanvas scene. */
@Serializable
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
) {
    fun terkunci(): Transform {
        val w = width.coerceIn(0.05f, 1f)
        val h = height.coerceIn(0.05f, 1f)
        return copy(
            x = x.coerceIn(0f, 1f - w),
            y = y.coerceIn(0f, 1f - h),
            width = w,
            height = h,
        )
    }

    companion object {
        val PENUH = Transform()
    }
}

/** Satu sumber di dalam scene. Urutan di [Scene.sources] menentukan urutan layer. */
@Serializable
data class SceneSource(
    val id: String,
    val type: SourceType,
    val name: String,
    /** Identitas perangkat atau endpoint untuk menyambung ulang (id UVC, id Node, dsb). */
    val deviceKey: String? = null,
    val visible: Boolean = true,
    val transform: Transform = Transform(),
    val shape: LayerShape = LayerShape.KOTAK,
    /** Isi untuk overlay teks; URI gambar untuk overlay gambar. */
    val content: String? = null,
    val textSize: Float = 0.06f,
) {
    val isOverlay: Boolean get() = type == SourceType.TEXT || type == SourceType.IMAGE
}
