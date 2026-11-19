package com.studiopocket.model

import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val id: String,
    val name: String,
    /** Indeks 0 paling bawah; urutan menentukan z-order. */
    val sources: List<SceneSource> = emptyList(),
)
