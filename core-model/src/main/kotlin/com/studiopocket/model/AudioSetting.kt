package com.studiopocket.model

import kotlinx.serialization.Serializable

/** Mode monitoring yang disimpan di proyek; dipetakan ke core-audio oleh aplikasi. */
@Serializable
enum class MonitorModeSetting { MATI, HANYA_MONITOR, MONITOR_DAN_REKAM }

/** Pengaturan mixer per saluran. Saluran sendiri dibuat ulang saat aplikasi dibuka. */
@Serializable
data class AudioSetting(
    val id: String,
    val gain: Float = 1f,
    val muted: Boolean = false,
    val monitor: MonitorModeSetting = MonitorModeSetting.MATI,
)
