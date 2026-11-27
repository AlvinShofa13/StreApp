package com.studiopocket.source.uvc

import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera

/** Pemilihan ukuran preview. Murni, agar bisa diuji tanpa perangkat. */
internal object UvcFormat {
    const val FPS = 30

    /**
     * Urutan: 1080p30, 720p30, lalu resolusi terbesar pada 30 fps.
     * MJPEG didahulukan karena YUYV 1080p tidak muat di bandwidth USB 2.0.
     */
    fun pilih(sizes: List<Size>): Size? {
        val cocok = sizes.filter { it.fps == FPS || it.fpsList?.contains(FPS) == true }
        val kandidat = cocok.ifEmpty { sizes }
        val terbaik = kandidat.minWithOrNull(
            compareBy<Size> { kelas(it) }
                .thenByDescending { it.width.toLong() * it.height }
                .thenBy { if (it.type == UVCCamera.UVC_VS_FRAME_MJPEG) 0 else 1 }
                .thenByDescending { it.fps },
        ) ?: return null
        return terbaik.clone().apply { fps = if (cocok.isNotEmpty()) FPS else terbaik.fps }
    }

    private fun kelas(s: Size): Int = when {
        s.width == 1920 && s.height == 1080 -> 0
        s.width == 1280 && s.height == 720 -> 1
        else -> 2
    }
}
