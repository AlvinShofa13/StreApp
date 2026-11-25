package com.studiopocket.app.engine

import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import com.studiopocket.app.R
import com.studiopocket.core.audio.AudioEngine
import com.studiopocket.core.thermal.ThermalLevel
import com.studiopocket.model.Project
import com.studiopocket.model.ProjectJson
import com.studiopocket.model.SourceStatus
import com.studiopocket.source.remote.PairingInfo
import com.studiopocket.stream.RtmpPublisher
import java.io.File

enum class StorageOption(@StringRes val labelRes: Int, val dirName: String) {
    MOVIES(R.string.storage_movies, Environment.DIRECTORY_MOVIES),
    DOWNLOADS(R.string.storage_downloads, Environment.DIRECTORY_DOWNLOADS),
    DCIM(R.string.storage_dcim, Environment.DIRECTORY_DCIM),
    INTERNAL(R.string.storage_internal, ""),
}

enum class SourceMode(val label: String) {
    ALL("Semua Sumber"),
    UVC_ONLY("Hanya Capture Card UVC"),
    CAMERA_ONLY("Hanya Kamera Depan"),
    TEST_PATTERN_ONLY("Hanya Pola Uji"),
}

enum class RecordingResolution(
    @StringRes val labelRes: Int,
    val shortLabel: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
) {
    RES_480P_30(R.string.res_480p30, "480p 30fps", 854, 480, 30, 2_000),
    RES_720P_30(R.string.res_720p30, "720p 30fps", 1280, 720, 30, 4_000),
    RES_720P_60(R.string.res_720p60, "720p 60fps", 1280, 720, 60, 6_000),
    RES_1080P_30(R.string.res_1080p30, "1080p 30fps", 1920, 1080, 30, 6_000),
    RES_1080P_60(R.string.res_1080p60, "1080p 60fps", 1920, 1080, 60, 10_000),
}

data class StreamUi(
    val profileName: String = "",
    val status: RtmpPublisher.Status = RtmpPublisher.Status.BERHENTI,
    val message: String = "",
    val kbps: Int = 0,
    val seconds: Long = 0,
) {
    val active: Boolean get() = status == RtmpPublisher.Status.SIARAN || status == RtmpPublisher.Status.MENYAMBUNG
}

data class EngineState(
    val project: Project = Project(),
    val recording: Boolean = false,
    val recordingMs: Long = 0,
    val recordingFile: String? = null,
    val markers: Int = 0,
    val stream: StreamUi = StreamUi(),
    val levels: Map<String, Float> = emptyMap(),
    val audioChannels: List<AudioEngine.ChannelInfo> = emptyList(),
    val sourceStatus: Map<String, SourceStatus> = emptyMap(),
    val thermal: ThermalLevel = ThermalLevel.NORMAL,
    val batteryPercent: Int = 0,
    val batteryTempC: Double? = null,
    val storageFreeMb: Long = 0,
    val headphones: Boolean = false,
    val hasScreen: Boolean = false,
    val remotePairing: PairingInfo? = null,
    val remoteNodes: List<String> = emptyList(),
    val errorRes: Int? = null,
    val lastFile: File? = null,
    val reviewFile: File? = null,
    val storageOption: StorageOption = StorageOption.MOVIES,
    val sourceMode: SourceMode = SourceMode.ALL,
    val recordingResolution: RecordingResolution = RecordingResolution.RES_720P_30,
    val canvasPortrait: Boolean = false,
    val previewEnabled: Boolean = true,
    val previewLowPower: Boolean = false,
    val selectedSourceId: String? = null,
) {
    val outputWidth: Int get() = if (canvasPortrait) recordingResolution.height else recordingResolution.width
    val outputHeight: Int get() = if (canvasPortrait) recordingResolution.width else recordingResolution.height
}

/** Lokasi berkas proyek JSON. Membersihkan pola uji bawaan lama saat migrasi. */
class ProjectStore(context: Context) {
    private val file = File(context.filesDir, "project.json")

    fun load(defaultName: String): Project {
        if (!file.exists()) return Project.bawaan(defaultName)
        return try {
            val loaded = ProjectJson.decode(file.readText())
            var changed = false
            val cleanedScenes = loaded.scenes.map { scene ->
                val hasLegacyTest = scene.sources.any { it.id == "test_pattern" }
                if (hasLegacyTest) {
                    changed = true
                    scene.copy(sources = scene.sources.filterNot { it.id == "test_pattern" })
                } else {
                    scene
                }
            }
            val result = if (changed) loaded.copy(scenes = cleanedScenes) else loaded
            if (changed) save(result)
            result
        } catch (_: Throwable) {
            Project.bawaan(defaultName)
        }
    }

    fun save(project: Project) {
        try {
            file.writeText(ProjectJson.encode(project))
        } catch (_: Throwable) {
        }
    }
}
