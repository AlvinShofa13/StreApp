package com.studiopocket.core.thermal

enum class LoadLevel { RINGAN, SEDANG, BERAT, MAKSIMUM }

data class LoadEstimate(
    val level: LoadLevel,
    val score: Float,
    val saranHemat: Boolean,
)

/** Estimasi beban sebelum merekam, dari kombinasi sumber dan preset keluaran. */
object LoadEstimator {

    fun estimasi(
        jumlahUvc: Int,
        jumlahKamera: Int,
        jumlahLayar: Int,
        jumlahNode: Int,
        jumlahTest: Int,
        lebar: Int,
        fps: Int,
    ): LoadEstimate {
        val skorSumber = jumlahUvc * 2.0 + jumlahLayar * 2.0 + jumlahNode * 1.5 +
            jumlahKamera * 1.2 + jumlahTest * 0.1
        val faktorResolusi = if (lebar >= 1920) 1.5 else 1.0
        val faktorFps = if (fps >= 60) 1.8 else 1.0
        val skor = (skorSumber * faktorResolusi * faktorFps).toFloat()
        val level = when {
            skor < 3f -> LoadLevel.RINGAN
            skor < 6f -> LoadLevel.SEDANG
            skor < 9f -> LoadLevel.BERAT
            else -> LoadLevel.MAKSIMUM
        }
        return LoadEstimate(level, skor, level == LoadLevel.BERAT || level == LoadLevel.MAKSIMUM)
    }
}
