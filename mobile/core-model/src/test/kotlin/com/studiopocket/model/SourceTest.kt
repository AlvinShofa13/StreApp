package com.studiopocket.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceTest {

    private class SumberPalsu : Source {
        override val id = "s1"
        override val name = "Palsu"
        override var status: SourceStatus = SourceStatus.SIAGA
        override var statusListener: Source.StatusListener? = null
        override var frameSink: FrameSink? = null

        fun terputus() {
            status = SourceStatus.TERPUTUS
            statusListener?.onStatusChanged(this, status)
        }

        override fun start() {
            status = SourceStatus.AKTIF
            statusListener?.onStatusChanged(this, status)
        }

        override fun stop() {
            status = SourceStatus.SIAGA
            statusListener?.onStatusChanged(this, status)
        }
    }

    @Test
    fun `start dan stop mengubah status dan memanggil listener`() {
        val sumber = SumberPalsu()
        val dicatat = mutableListOf<SourceStatus>()
        sumber.statusListener = object : Source.StatusListener {
            override fun onStatusChanged(source: Source, status: SourceStatus) {
                dicatat.add(status)
            }
        }

        sumber.start()
        assertEquals(SourceStatus.AKTIF, sumber.status)
        sumber.terputus()
        assertEquals(SourceStatus.TERPUTUS, sumber.status)
        sumber.stop()
        assertEquals(SourceStatus.SIAGA, sumber.status)
        assertEquals(listOf(SourceStatus.AKTIF, SourceStatus.TERPUTUS, SourceStatus.SIAGA), dicatat)
    }

    @Test
    fun `tanpa listener tidak gagal`() {
        val sumber = SumberPalsu()
        sumber.statusListener = null
        sumber.start()
        assertEquals(SourceStatus.AKTIF, sumber.status)
    }

    @Test
    fun `frame sink bisa dipasang dan dilepas`() {
        val sumber = SumberPalsu()
        val sink = object : FrameSink {}
        sumber.frameSink = sink
        assertSame(sink, sumber.frameSink)
        sumber.frameSink = null
        assertNull(sumber.frameSink)
    }

    @Test
    fun `transform bawaan menutup seluruh kanvas`() {
        val t = Transform()
        assertEquals(0f, t.x)
        assertEquals(0f, t.y)
        assertEquals(1f, t.width)
        assertEquals(1f, t.height)
        assertNull(SceneSource("x", SourceType.TEST, "T").deviceKey)
    }
}
