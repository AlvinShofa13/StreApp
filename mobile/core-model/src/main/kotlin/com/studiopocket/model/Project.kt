package com.studiopocket.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class SceneTransition { CUT, FADE }

@Serializable
data class Project(
    val version: Int = 2,
    val scenes: List<Scene> = emptyList(),
    val activeSceneId: String? = null,
    val presets: List<Preset> = Presets.bawaan,
    val activePresetId: String = Presets.SEIMBANG.id,
    val transition: SceneTransition = SceneTransition.FADE,
    val transitionMs: Int = 300,
    val audioSettings: List<AudioSetting> = emptyList(),
    val streamProfileId: String? = null,
) {
    init {
        require(scenes.map { it.id }.toSet().size == scenes.size) { "id scene ganda" }
        require(activeSceneId == null || scenes.any { it.id == activeSceneId }) {
            "activeSceneId tidak ada di daftar scene"
        }
    }

    val activeScene: Scene? get() = scenes.firstOrNull { it.id == activeSceneId }
    val activePreset: Preset get() = Presets.cari(activePresetId)

    fun addScene(scene: Scene, index: Int = scenes.size): Project {
        require(index in 0..scenes.size) { "index di luar jangkauan" }
        val baru = scenes.toMutableList().apply { add(index, scene) }
        return copy(scenes = baru, activeSceneId = activeSceneId ?: scene.id)
    }

    /** Gandakan scene [id] tepat setelah aslinya. Nama baru disuplai pemanggil (teks UI). */
    fun duplicateScene(id: String, newName: String, newId: String = newId()): Project {
        val asal = scenes.firstOrNull { it.id == id } ?: return this
        val posisi = scenes.indexOf(asal)
        val salinanSources = asal.sources.map { it.copy(id = newId()) }
        val salinan = asal.copy(id = newId, name = newName, sources = salinanSources)
        val baru = scenes.toMutableList().apply { add(posisi + 1, salinan) }
        return copy(scenes = baru)
    }

    fun moveScene(id: String, toIndex: Int): Project {
        val dari = scenes.indexOfFirst { it.id == id }
        if (dari < 0 || toIndex !in 0 until scenes.size) return this
        val baru = scenes.toMutableList().apply { add(toIndex, removeAt(dari)) }
        return copy(scenes = baru)
    }

    fun removeScene(id: String): Project {
        val baru = scenes.filterNot { it.id == id }
        val aktif = if (activeSceneId == id) baru.firstOrNull()?.id else activeSceneId
        return copy(scenes = baru, activeSceneId = aktif)
    }

    fun renameScene(id: String, name: String): Project {
        val bersih = name.trim()
        if (bersih.isEmpty()) return this
        val baru = scenes.map { if (it.id == id) it.copy(name = bersih) else it }
        return copy(scenes = baru)
    }

    fun setActiveScene(id: String): Project =
        if (scenes.any { it.id == id }) copy(activeSceneId = id) else this

    fun updateScene(scene: Scene): Project =
        if (scenes.none { it.id == scene.id }) this
        else copy(scenes = scenes.map { if (it.id == scene.id) scene else it })

    fun addSource(sceneId: String, source: SceneSource): Project {
        val s = scenes.firstOrNull { it.id == sceneId } ?: return this
        val baruScene = s.addSource(source)
        return copy(scenes = scenes.map { if (it.id == sceneId) baruScene else it })
    }

    fun addSource(
        sceneId: String,
        type: SourceType,
        name: String,
        deviceKey: String? = null,
        content: String? = null,
        sourceId: String = newId(),
    ): Project {
        val s = scenes.firstOrNull { it.id == sceneId } ?: return this
        val src = SceneSource(
            id = sourceId,
            type = type,
            name = name,
            deviceKey = deviceKey,
            content = content,
        )
        val baruScene = s.addSource(src)
        return copy(scenes = scenes.map { if (it.id == sceneId) baruScene else it })
    }

    fun removeSource(sceneId: String, sourceId: String): Project {
        val s = scenes.firstOrNull { it.id == sceneId } ?: return this
        val baruScene = s.removeSource(sourceId)
        return copy(scenes = scenes.map { if (it.id == sceneId) baruScene else it })
    }

    fun moveSource(sceneId: String, sourceId: String, toIndex: Int): Project {
        val s = scenes.firstOrNull { it.id == sceneId } ?: return this
        val baruScene = s.moveSource(sourceId, toIndex)
        return copy(scenes = scenes.map { if (it.id == sceneId) baruScene else it })
    }

    fun updateSource(sceneId: String, source: SceneSource): Project {
        val s = scenes.firstOrNull { it.id == sceneId } ?: return this
        val baruScene = s.updateSource(source)
        return copy(scenes = scenes.map { if (it.id == sceneId) baruScene else it })
    }

    fun setPreset(id: String): Project =
        if (presets.any { it.id == id }) copy(activePresetId = id) else this

    fun setTransition(transition: SceneTransition, ms: Int = transitionMs): Project =
        copy(transition = transition, transitionMs = ms.coerceIn(0, 1000))

    fun withAudio(settings: List<AudioSetting>): Project = copy(audioSettings = settings)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** Proyek baru dengan satu scene kosong yang langsung aktif. */
        fun baru(sceneName: String, sceneId: String = newId()): Project {
            val scene = Scene(sceneId, sceneName)
            return Project(scenes = listOf(scene), activeSceneId = scene.id)
        }

        /** Proyek bawaan bersih: Capture Card layar penuh dan Kamera Depan PiP. */
        fun bawaan(sceneName: String, sceneId: String = newId()): Project {
            val defaultSources = listOf(
                SceneSource(
                    id = "uvc",
                    type = SourceType.UVC,
                    name = "Capture Card UVC",
                    transform = Transform(0f, 0f, 1f, 1f),
                ),
                SceneSource(
                    id = "camera_front",
                    type = SourceType.CAMERA,
                    name = "Kamera Depan",
                    transform = Transform(0.66f, 0.62f, 0.30f, 0.33f),
                    shape = LayerShape.SUDUT_MEMBULAT,
                ),
            )
            val scene = Scene(sceneId, sceneName, sources = defaultSources)
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
