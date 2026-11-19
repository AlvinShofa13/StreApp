package com.studiopocket.source.uvc

import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcFormatTest {

    private fun ukuran(type: Int, w: Int, h: Int, fps: Int, vararg daftarFps: Int) =
        Size(type, w, h, fps, daftarFps.toList())

    private val mjpeg = UVCCamera.UVC_VS_FRAME_MJPEG
    private val yuyv = UVCCamera.UVC_VS_FRAME_UNCOMPRESSED

    @Test
    fun memilih1080p30() {
        val hasil = UvcFormat.pilih(
            listOf(
                ukuran(mjpeg, 1280, 720, 30, 30),
                ukuran(mjpeg, 1920, 1080, 30, 30),
            ),
        )
        assertEquals(1920, hasil?.width)
        assertEquals(1080, hasil?.height)
        assertEquals(30, hasil?.fps)
    }

    @Test
    fun memilih720p30Daripada1080p60() {
        val hasil = UvcFormat.pilih(
            listOf(
                ukuran(mjpeg, 1920, 1080, 60, 60),
                ukuran(mjpeg, 1280, 720, 30, 30),
            ),
        )
        assertEquals(1280, hasil?.width)
        assertEquals(30, hasil?.fps)
    }

    @Test
    fun memakaiFps30WalauDefault60() {
        val hasil = UvcFormat.pilih(listOf(ukuran(mjpeg, 1920, 1080, 60, 30, 60)))
        assertEquals(30, hasil?.fps)
    }

    @Test
    fun mendahulukanMjpeg() {
        val hasil = UvcFormat.pilih(
            listOf(
                ukuran(yuyv, 1920, 1080, 30, 30),
                ukuran(mjpeg, 1920, 1080, 30, 30),
            ),
        )
        assertEquals(mjpeg, hasil?.type)
    }

    @Test
    fun mendukungYuyvBilaTanpaMjpeg() {
        val hasil = UvcFormat.pilih(listOf(ukuran(yuyv, 1920, 1080, 30, 30)))
        assertEquals(yuyv, hasil?.type)
        assertEquals(1920, hasil?.width)
    }

    @Test
    fun cadanganResolusiTerbesarBilaTanpa30Fps() {
        val hasil = UvcFormat.pilih(
            listOf(
                ukuran(mjpeg, 640, 480, 25, 25),
                ukuran(mjpeg, 1920, 1080, 25, 25),
            ),
        )
        assertEquals(1920, hasil?.width)
        assertEquals(25, hasil?.fps)
    }

    @Test
    fun kosongMenghasilkanNull() {
        assertNull(UvcFormat.pilih(emptyList()))
    }

    @Test
    fun hasilBukanObjekYangSama() {
        val asal = ukuran(mjpeg, 1920, 1080, 30, 30)
        val hasil = UvcFormat.pilih(listOf(asal))
        assertTrue(hasil !== asal)
    }
}
