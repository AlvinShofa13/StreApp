package com.studiopocket.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { UVC, CAMERA, SCREEN, REMOTE, TEST }

/** Posisi dan ukuran dalam koordinat ternormalisasi 0..1 terhadap kanvas scene. */
@Serializable
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
)

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
)
