package com.studiopocket.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingTest {

    @Test
    fun `tulis dan baca berurutan`() {
        val ring = PcmRing(channels = 2, frameCapacity = 8)
        val data = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        ring.write(data, 4)
        val out = ShortArray(8)
        assertEquals(4, ring.read(out, 4))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), out.toList().map { it.toInt() })
    }

    @Test
    fun `baca sebagian menyisakan data`() {
        val ring = PcmRing(channels = 2, frameCapacity = 8)
        ring.write(shortArrayOf(1, 1, 2, 2, 3, 3, 4, 4), 4)
        val out = ShortArray(4)
        assertEquals(2, ring.read(out, 2))
        assertEquals(2, ring.available())
        assertEquals(2, ring.read(out, 2))
        assertEquals(3, out[0].toInt())
    }

    @Test
    fun `wrap buffer bekerja`() {
        val ring = PcmRing(channels = 1, frameCapacity = 4)
        ring.write(shortArrayOf(1, 2, 3), 3)
        assertEquals(2, ring.read(ShortArray(2), 2))
        ring.write(shortArrayOf(4, 5, 6), 3)
        val out = ShortArray(4)
        assertEquals(4, ring.read(out, 4))
        assertEquals(listOf(3, 4, 5, 6), out.toList().map { it.toInt() })
    }

    @Test
    fun `buffer penuh membuang frame tertua`() {
        val ring = PcmRing(channels = 1, frameCapacity = 4)
        ring.write(shortArrayOf(1, 2, 3, 4), 4)
        ring.write(shortArrayOf(5, 6), 2)
        val out = ShortArray(4)
        assertEquals(4, ring.read(out, 4))
        assertEquals(listOf(3, 4, 5, 6), out.toList().map { it.toInt() })
        assertEquals(2L, ring.dropped)
    }
}
