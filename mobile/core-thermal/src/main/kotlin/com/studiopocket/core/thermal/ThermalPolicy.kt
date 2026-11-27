package com.studiopocket.core.thermal

/** Tingkat termal yang dipakai kebijakan aplikasi. */
enum class ThermalLevel { NORMAL, RINGAN, SEDANG, BERAT, KRITIS }

/**
 * Perintah penurunan kualitas. Modul lain hanya membaca nilai ini, tidak membaca suhu sendiri.
 */
data class ThermalAction(
    val level: ThermalLevel,
    val previewFps: Int,
    val previewScale: Float,
    val encoderFps: Int,
    val bitrateScale: Float,
    val stopRecording: Boolean,
)

/** Kebijakan murni: status termal sistem menjadi perintah kualitas. */
object ThermalPolicy {

    fun levelFromStatus(status: Int): ThermalLevel = when (status) {
        0 -> ThermalLevel.NORMAL // THERMAL_STATUS_NONE
        1 -> ThermalLevel.RINGAN // LIGHT
        2 -> ThermalLevel.SEDANG // MODERATE
        3 -> ThermalLevel.BERAT // SEVERE
        4, 5 -> ThermalLevel.KRITIS // CRITICAL / SHUTDOWN
        else -> ThermalLevel.NORMAL
    }

    fun action(level: ThermalLevel): ThermalAction = when (level) {
        ThermalLevel.NORMAL -> ThermalAction(
            level, previewFps = 30, previewScale = 1f, encoderFps = 30,
            bitrateScale = 1f, stopRecording = false,
        )

        ThermalLevel.RINGAN -> ThermalAction(
            level, previewFps = 20, previewScale = 1f, encoderFps = 30,
            bitrateScale = 1f, stopRecording = false,
        )

        ThermalLevel.SEDANG -> ThermalAction(
            level, previewFps = 15, previewScale = 0.75f, encoderFps = 30,
            bitrateScale = 0.85f, stopRecording = false,
        )

        ThermalLevel.BERAT -> ThermalAction(
            level, previewFps = 10, previewScale = 0.5f, encoderFps = 20,
            bitrateScale = 0.65f, stopRecording = false,
        )

        ThermalLevel.KRITIS -> ThermalAction(
            level, previewFps = 5, previewScale = 0.5f, encoderFps = 15,
            bitrateScale = 0.5f, stopRecording = true,
        )
    }

    /** Naik level langsung ditanggapi; turun satu langkah ditanggapi, lompatan turun besar beri jeda. */
    fun perluTurun(sekarang: ThermalLevel, baru: ThermalLevel): Boolean =
        baru.ordinal > sekarang.ordinal

    fun perluNaik(sekarang: ThermalLevel, baru: ThermalLevel): Boolean =
        baru.ordinal < sekarang.ordinal
}
