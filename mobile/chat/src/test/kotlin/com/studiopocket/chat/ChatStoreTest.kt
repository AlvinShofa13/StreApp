package com.studiopocket.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    private fun pesan(nomor: Int, teks: String = "halo $nomor", role: ChatRole = ChatRole.PENONTON, author: String = "Rina") =
        ChatMessage("m$nomor", author, teks, role, nomor.toLong())

    @Test
    fun `batas dua ratus pesan membuang yang tertua`() {
        val store = ChatStore(maxMessages = 200)
        repeat(250) { store.tambah(pesan(it)) }
        assertEquals(200, store.size)
        assertEquals("m50", store.semua().first().id)
        assertEquals("m249", store.semua().last().id)
    }

    @Test
    fun `penyaring bot membuang bot dan nama berakhiran bot`() {
        val store = ChatStore(filterBots = true)
        assertFalse(store.tambah(pesan(1, role = ChatRole.BOT)))
        assertFalse(store.tambah(pesan(2, author = "streambot")))
        assertTrue(store.tambah(pesan(3)))
        assertEquals(1, store.size)
    }

    @Test
    fun `perintah dibuang saat penyaring aktif`() {
        val store = ChatStore(filterCommands = true)
        assertFalse(store.tambah(pesan(1, "!command")))
        assertTrue(store.tambah(pesan(2, "halo semua")))
        assertFalse(store.tambah(pesan(3, "!")))
    }

    @Test
    fun `terbaru mengembalikan ekor tanpa mengubah isi`() {
        val store = ChatStore()
        repeat(10) { store.tambah(pesan(it)) }
        assertEquals(listOf("m7", "m8", "m9"), store.terbaru(3).map { it.id })
        assertEquals(10, store.size)
    }
}
