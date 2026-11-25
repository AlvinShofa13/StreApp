package com.studiopocket.source.screen

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus

/**
 * Rekam layar lewat MediaProjection ke SurfaceTexture kompositor.
 * Izin MediaProjection tidak dapat dipakai ulang; bila dihentikan sistem, sumber
 * berstatus TERPUTUS sampai pengguna memberi izin baru.
 */
class ScreenSource(
    context: Context,
    override val name: String,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 30,
) : Source {
    override val id: String get() = "screen"
    override var status: SourceStatus = SourceStatus.SIAGA
        private set
    override var statusListener: Source.StatusListener? = null
    override var frameSink: FrameSink? = null

    private val appContext = context.applicationContext
    private val densityDpi = appContext.resources.displayMetrics.densityDpi
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var running = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection dihentikan")
            lepasDisplay()
            projection = null
            if (running) setStatus(SourceStatus.TERPUTUS)
        }
    }

    fun attachProjection(mediaProjection: MediaProjection) {
        projection?.unregisterCallback(projectionCallback)
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        if (running) buatDisplay()
    }

    /** Dipanggil compositor saat layer dibuat. */
    fun setPreview(surfaceTexture: SurfaceTexture?) {
        if (texture === surfaceTexture) return
        lepasDisplay()
        texture = surfaceTexture
        if (running) buatDisplay()
    }

    override fun start() {
        if (running) return
        running = true
        if (projection == null) {
            setStatus(SourceStatus.GALAT)
            return
        }
        buatDisplay()
    }

    private fun buatDisplay() {
        val proj = projection ?: return
        val tex = texture ?: return
        if (virtualDisplay != null) return
        try {
            tex.setDefaultBufferSize(width, height)
            val s = Surface(tex)
            surface = s
            virtualDisplay = proj.createVirtualDisplay(
                "StreAppScreen",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                s,
                null,
                null,
            )
            setStatus(SourceStatus.AKTIF)
            Log.i(TAG, "Virtual display $width x $height @ $fps fps dimulai")
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal membuat virtual display", t)
            lepasDisplay()
            setStatus(SourceStatus.GALAT)
        }
    }

    private fun lepasDisplay() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            surface?.release()
        } catch (_: Throwable) {
        }
        surface = null
    }

    override fun stop() {
        if (!running) return
        running = false
        lepasDisplay()
        projection?.unregisterCallback(projectionCallback)
        projection = null
        texture = null
        setStatus(SourceStatus.SIAGA)
    }

    private fun setStatus(nilai: SourceStatus) {
        if (status == nilai) return
        status = nilai
        statusListener?.onStatusChanged(this, nilai)
    }

    companion object {
        private const val TAG = "SP-ScreenSource"
    }
}
