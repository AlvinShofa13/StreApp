package com.studiopocket.model

enum class SceneTemplate { LAYAR_PENUH, KAMERA_PENUH, GAMEPLAY_KAMERA, LAYAR_KAMERA_SUDUT, DUA_KAMERA }

/**
 * Tata letak bawaan. Murni: hanya mengubah posisi/visibilitas sumber yang sudah ada.
 */
object SceneTemplates {

    fun susun(template: SceneTemplate, sources: List<SceneSource>): List<SceneSource> {
        if (sources.isEmpty()) return sources
        val kamera = sources.indexOfFirst { it.type == SourceType.CAMERA }
        val layar = sources.indexOfFirst { it.type == SourceType.SCREEN }
        val utama = when (template) {
            SceneTemplate.KAMERA_PENUH -> if (kamera >= 0) kamera else 0
            SceneTemplate.LAYAR_KAMERA_SUDUT -> if (layar >= 0) layar else 0
            else -> 0
        }
        val kedua = when (template) {
            SceneTemplate.GAMEPLAY_KAMERA,
            SceneTemplate.LAYAR_KAMERA_SUDUT,
            -> if (kamera >= 0 && kamera != utama) kamera else if (utama == 0 && sources.size > 1) 1 else -1

            SceneTemplate.DUA_KAMERA -> if (kamera >= 0 && kamera != utama) kamera else {
                val lain = sources.indices.firstOrNull { it != utama } ?: -1
                lain
            }

            else -> -1
        }

        return sources.mapIndexed { i, s ->
            when {
                i == utama && template == SceneTemplate.DUA_KAMERA -> s.copy(
                    visible = true,
                    transform = Transform(0f, 0f, 0.5f, 1f),
                    shape = LayerShape.KOTAK,
                )

                i == utama -> s.copy(
                    visible = true,
                    transform = Transform.PENUH,
                    shape = LayerShape.KOTAK,
                )

                i == kedua && template == SceneTemplate.DUA_KAMERA -> s.copy(
                    visible = true,
                    transform = Transform(0.5f, 0f, 0.5f, 1f),
                    shape = LayerShape.KOTAK,
                )

                i == kedua -> s.copy(
                    visible = true,
                    transform = Transform(0.63f, 0.63f, 0.33f, 0.33f),
                    shape = LayerShape.SUDUT_MEMBULAT,
                )

                else -> s.copy(visible = false)
            }
        }
    }
}
