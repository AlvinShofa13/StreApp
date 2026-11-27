package com.studiopocket.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEditingTest {

    private fun sumber(id: String, type: SourceType = SourceType.TEST) =
        SceneSource(id, type, id, deviceKey = id)

    @Test
    fun `transform terkunci tetap di dalam kanvas`() {
        val t = Transform(x = 0.9f, y = -0.2f, width = 0.4f, height = 0.3f).terkunci()
        assertEquals(0.6f, t.x, 0.0001f)
        assertEquals(0f, t.y, 0.0001f)
        assertEquals(0.4f, t.width, 0.0001f)
    }

    @Test
    fun `ubah ukuran dan urutan sumber`() {
        val p = Project.baru("A", sceneId = "a")
            .addSource("a", sumber("s1"))
            .addSource("a", sumber("s2"))
            .addSource("a", sumber("s3"))
        val geser = p.moveSource("a", "s3", 0)
        assertEquals(listOf("s3", "s1", "s2"), geser.activeScene!!.sources.map { it.id })
        val diubah = geser.updateSource("a", sumber("s1").copy(transform = Transform(0.5f, 0.5f, 0.5f, 0.5f)))
        assertEquals(0.5f, diubah.activeScene!!.source("s1")!!.transform.x)
        val hapus = diubah.removeSource("a", "s1")
        assertEquals(listOf("s3", "s2"), hapus.activeScene!!.sources.map { it.id })
    }

    @Test
    fun `rename dan preset hanya menerima nilai sah`() {
        val p = Project.baru("A", sceneId = "a")
        assertEquals("Baru", p.renameScene("a", "  Baru  ").scenes[0].name)
        assertEquals(p, p.renameScene("a", "  "))
        assertEquals("hemat", p.setPreset("hemat").activePresetId)
        assertEquals(p, p.setPreset("tidak-ada"))
    }

    @Test
    fun `template gameplay menaruh kamera sebagai pip kanan bawah`() {
        val hasil = SceneTemplates.susun(
            SceneTemplate.GAMEPLAY_KAMERA,
            listOf(sumber("cap", SourceType.UVC), sumber("cam", SourceType.CAMERA)),
        )
        assertEquals(Transform.PENUH, hasil[0].transform)
        assertTrue(hasil[1].visible)
        assertEquals(0.63f, hasil[1].transform.x, 0.0001f)
        assertEquals(LayerShape.SUDUT_MEMBULAT, hasil[1].shape)
    }

    @Test
    fun `template dua kamera membagi layar dan menyembunyikan sisanya`() {
        val hasil = SceneTemplates.susun(
            SceneTemplate.DUA_KAMERA,
            listOf(
                sumber("a", SourceType.CAMERA),
                sumber("b", SourceType.CAMERA),
                sumber("c", SourceType.UVC),
            ),
        )
        assertEquals(0f, hasil[0].transform.x)
        assertEquals(0.5f, hasil[0].transform.width)
        assertEquals(0.5f, hasil[1].transform.x)
        assertFalse(hasil[2].visible)
    }

    @Test
    fun `layer teks disimpan sebagai overlay`() {
        val teks = SceneSource("t", SourceType.TEXT, "Judul", content = "Halo", textSize = 0.08f)
        assertTrue(teks.isOverlay)
        val p = Project.baru("A", sceneId = "a").addSource("a", teks)
        val teks2 = ProjectJson.decode(ProjectJson.encode(p)).activeScene!!.source("t")!!
        assertEquals("Halo", teks2.content)
        assertEquals(0.08f, teks2.textSize)
    }
}
