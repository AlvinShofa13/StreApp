package com.studiopocket.model

import kotlinx.serialization.Serializable

@Serializable
enum class QualityPreset { HEMAT, SEIMBANG, TINGGI }

@Serializable
data class EncoderSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int = 128,
)

@Serializable
data class Preset(
    val id: String,
    val quality: QualityPreset,
    val encoder: EncoderSettings,
)

object Presets {
    // Angka awal; dikalibrasi ulang dari hasil uji T1.3 di Helio G99.
    val HEMAT = Preset("hemat", QualityPreset.HEMAT, EncoderSettings(1280, 720, 30, 2_500))
    val SEIMBANG = Preset("seimbang", QualityPreset.SEIMBANG, EncoderSettings(1280, 720, 30, 4_000))
    val TINGGI = Preset("tinggi", QualityPreset.TINGGI, EncoderSettings(1920, 1080, 30, 6_000))

    val bawaan: List<Preset> = listOf(HEMAT, SEIMBANG, TINGGI)
}
