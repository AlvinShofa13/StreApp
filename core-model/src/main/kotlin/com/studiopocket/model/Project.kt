package com.studiopocket.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Project(
    val version: Int = 1,
    val scenes: List<Scene> = emptyList(),
    val activeSceneId: String? = null,
    val presets: List<Preset> = Presets.bawaan,
) {
    init {
        require(scenes.map { it.id }.toSet().size == scenes.size) { "id scene ganda" }
        require(activeSceneId == null || scenes.any { it.id == activeSceneId }) {
            "activeSceneId tidak ada di daftar scene"
        }
    }

    fun addScene(scene: Scene, index: Int = scenes.size): Project {
        require(index in 0..scenes.size) { "index di luar jangkauan" }
        val baru = scenes.toMutableList().apply { add(index, scene) }
        return copy(scenes = baru, activeSceneId = activeSceneId ?: scene.id)
    }

    /** Gandakan scene [id] tepat setelah aslinya. Nama baru disuplai pemanggil (teks UI). */
    fun duplicateScene(id: String, newName: String, newId: String = newId()): Project {
        val posisi = scenes.indexOfFirst { it.id == id }
        if (posisi < 0) return this
        val salinan = scenes[posisi].copy(id = newId, name = newName)
        val baru = scenes.toMutableList().apply { add(posisi + 1, salinan) }
        return copy(scenes = baru)
    }

    fun moveScene(id: String, toIndex: Int): Project {
        val dari = scenes.indexOfFirst { it.id == id }
        if (dari < 0 || toIndex !in scenes.indices) return this
        val baru = scenes.toMutableList().apply { add(toIndex, removeAt(dari)) }
        return copy(scenes = baru)
    }

    fun removeScene(id: String): Project {
        if (scenes.none { it.id == id }) return this
        val baru = scenes.filterNot { it.id == id }
        val aktif = if (activeSceneId == id) baru.firstOrNull()?.id else activeSceneId
        return copy(scenes = baru, activeSceneId = aktif)
    }

    fun setActiveScene(id: String): Project =
        if (scenes.any { it.id == id }) copy(activeSceneId = id) else this

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** Proyek baru dengan satu scene kosong yang langsung aktif. */
        fun baru(sceneName: String, sceneId: String = newId()): Project {
            val scene = Scene(sceneId, sceneName)
            return Project(scenes = listOf(scene), activeSceneId = scene.id)
        }
    }
}

object ProjectJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: Project): String = json.encodeToString(project)

    fun decode(teks: String): Project = json.decodeFromString(teks)
}
