package com.studiopocket.model

import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val id: String,
    val name: String,
    /** Indeks 0 paling bawah; urutan menentukan z-order. */
    val sources: List<SceneSource> = emptyList(),
) {
    fun source(id: String): SceneSource? = sources.firstOrNull { it.id == id }

    fun updateSource(source: SceneSource): Scene =
        if (sources.none { it.id == source.id }) this
        else copy(sources = sources.map { if (it.id == source.id) source else it })

    fun addSource(source: SceneSource): Scene = copy(sources = sources + source)

    fun removeSource(id: String): Scene = copy(sources = sources.filterNot { it.id == id })

    fun moveSource(id: String, toIndex: Int): Scene {
        val dari = sources.indexOfFirst { it.id == id }
        if (dari < 0 || toIndex !in sources.indices) return this
        val baru = sources.toMutableList().apply { add(toIndex, removeAt(dari)) }
        return copy(sources = baru)
    }
}
