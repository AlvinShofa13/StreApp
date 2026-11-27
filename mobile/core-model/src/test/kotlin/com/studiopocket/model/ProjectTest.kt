package com.studiopocket.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTest {

    private fun sumber(id: String = "s1", type: SourceType = SourceType.TEST) =
        SceneSource(id, type, "Tes", deviceKey = "dev-$id")

    private fun scene(id: String, sumber: List<SceneSource> = emptyList()) =
        Scene(id, "Scene $id", sumber)

    @Test
    fun `proyek baru punya satu scene aktif`() {
        val p = Project.baru("Utama", sceneId = "a")
        assertEquals(1, p.scenes.size)
        assertEquals("a", p.activeSceneId)
        assertEquals(2, p.version)
    }

    @Test
    fun `addScene menambah di akhir dan menyisipkan di index`() {
        val p = Project.baru("A", sceneId = "a")
            .addScene(scene("b"))
            .addScene(scene("c"), index = 0)

        assertEquals(listOf("c", "a", "b"), p.scenes.map { it.id })
        assertEquals("a", p.activeSceneId) // scene aktif tidak berubah
        assertThrows(IllegalArgumentException::class.java) { p.addScene(scene("x"), index = 9) }
    }

    @Test
    fun `duplicateScene menyalin tepat setelah aslinya`() {
        val p = Project.baru("A", sceneId = "a")
            .addScene(scene("b", listOf(sumber())))
            .addScene(scene("c"))
            .duplicateScene("b", newName = "Salinan", newId = "b2")

        assertEquals(listOf("a", "b", "b2", "c"), p.scenes.map { it.id })
        assertEquals("Salinan", p.scenes[2].name)
        assertEquals(1, p.scenes[2].sources.size)
        assertEquals(p.scenes[1].sources[0].deviceKey, p.scenes[2].sources[0].deviceKey)
        assertTrue(p.scenes[1].sources[0].id != p.scenes[2].sources[0].id)
        assertEquals("a", p.activeSceneId)
    }

    @Test
    fun `duplicateScene id tak dikenal tidak mengubah apa pun`() {
        val p = Project.baru("A", sceneId = "a")
        assertEquals(p, p.duplicateScene("tidak-ada", newName = "X"))
    }

    @Test
    fun `moveScene mengubah urutan`() {
        val p = Project.baru("A", sceneId = "a")
            .addScene(scene("b"))
            .addScene(scene("c"))
        assertEquals(listOf("b", "c", "a"), p.moveScene("a", 2).scenes.map { it.id })
        assertEquals(listOf("b", "a", "c"), p.moveScene("a", 1).scenes.map { it.id })
        assertEquals(p, p.moveScene("tidak-ada", 0))
        assertEquals(p, p.moveScene("a", 5))
    }

    @Test
    fun `removeScene menghapus dan memilih scene aktif berikutnya`() {
        val p = Project.baru("A", sceneId = "a")
            .addScene(scene("b"))
            .addScene(scene("c"))

        val tanpaA = p.removeScene("a")
        assertEquals(listOf("b", "c"), tanpaA.scenes.map { it.id })
        assertEquals("b", tanpaA.activeSceneId)

        val tanpaC = p.removeScene("c")
        assertEquals(listOf("a", "b"), tanpaC.scenes.map { it.id })
        assertEquals("a", tanpaC.activeSceneId) // aktif bukan c, tetap

        assertEquals(p, p.removeScene("tidak-ada"))
    }

    @Test
    fun `setActiveScene hanya menerima id yang ada`() {
        val p = Project.baru("A", sceneId = "a").addScene(scene("b"))
        assertEquals("b", p.setActiveScene("b").activeSceneId)
        assertEquals(p, p.setActiveScene("zzz"))
    }

    @Test
    fun `json roundtrip mempertahankan seluruh isi`() {
        val p = Project.baru("Gameplay", sceneId = "g1")
            .addScene(
                scene(
                    "kamera",
                    listOf(
                        sumber("cam", SourceType.CAMERA),
                        sumber("cap", SourceType.UVC),
                        sumber("screen", SourceType.SCREEN),
                        sumber("node", SourceType.REMOTE),
                    ),
                ),
            )
            .addScene(scene("kosong"))
        val teks = ProjectJson.encode(p)
        assertEquals(p, ProjectJson.decode(teks))
    }

    @Test
    fun `json menolak scene aktif yatim dan id ganda`() {
        val yatim = """{"version":1,"scenes":[],"activeSceneId":"x"}"""
        assertThrows(IllegalArgumentException::class.java) { ProjectJson.decode(yatim) }

        val ganda = """{"version":1,"scenes":[
            {"id":"a","name":"A","sources":[]},
            {"id":"a","name":"B","sources":[]}],"activeSceneId":"a"}"""
        assertThrows(IllegalArgumentException::class.java) { ProjectJson.decode(ganda) }
    }

    @Test
    fun `json toleran terhadap field tak dikenal`() {
        val teks = """{"version":1,"scenes":[{"id":"a","name":"A","sources":[],"catatan":"baru"}],
            "activeSceneId":"a","tambahan":true}"""
        val p = ProjectJson.decode(teks)
        assertEquals("a", p.scenes.single().id)
    }

    @Test
    fun `preset bawaan lengkap dan unik`() {
        assertEquals(3, Presets.bawaan.size)
        assertEquals(
            3,
            Presets.bawaan.map { it.quality }.toSet().size,
        )
        assertEquals(3, Presets.bawaan.map { it.id }.toSet().size)
        assertTrue(Presets.bawaan.all { it.encoder.width <= 1920 && it.encoder.height <= 1080 })
        assertTrue(Presets.bawaan.all { it.encoder.fps == 30 || it.encoder.fps == 60 })
        assertEquals(30, Presets.HEMAT.encoder.fps)
        assertEquals(60, Presets.TINGGI.encoder.fps)
    }
}
