package com.studiopocket.core.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.studiopocket.model.FrameSink
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus

/**
 * Sumber overlay video lokal (MP4/WebM) yang merender langsung lewat MediaPlayer ke SurfaceTexture
 * kompositor (zero-copy GPU, tanpa salinan CPU) dengan pengulangan otomatis (loop).
 */
class VideoOverlaySource(
    context: Context,
    override val id: String,
    override val name: String,
    private val uriString: String,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null,
) : Source {
    private val appContext = context.applicationContext

    override var status: SourceStatus = SourceStatus.SIAGA
        private set
    override var statusListener: Source.StatusListener? = null
    override var frameSink: FrameSink? = null

    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var started = false
    private var muted = true

    fun setPreview(texture: SurfaceTexture?) {
        synchronized(lock) {
            if (surfaceTexture === texture) return
            surfaceTexture = texture
            if (started && texture != null) {
                siapkanPlayer(texture)
            }
        }
    }

    fun setMuted(isMuted: Boolean) {
        synchronized(lock) {
            muted = isMuted
            val vol = if (isMuted) 0f else 1f
            try {
                mediaPlayer?.setVolume(vol, vol)
            } catch (_: Throwable) {
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            val tex = surfaceTexture
            if (tex != null) {
                siapkanPlayer(tex)
            } else {
                setStatus(SourceStatus.SIAGA)
            }
        }
    }

    private fun siapkanPlayer(texture: SurfaceTexture) {
        lepasPlayer()
        if (uriString.isBlank()) {
            setStatus(SourceStatus.GALAT)
            return
        }
        try {
            val s = Surface(texture)
            val mp = MediaPlayer().apply {
                setDataSource(appContext, Uri.parse(uriString))
                setSurface(s)
                isLooping = true
                val vol = if (muted) 0f else 1f
                setVolume(vol, vol)
                setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        texture.setDefaultBufferSize(w, h)
                        onVideoSizeChanged?.invoke(w, h)
                    }
                }
                setOnPreparedListener { player ->
                    val w = player.videoWidth
                    val h = player.videoHeight
                    if (w > 0 && h > 0) {
                        texture.setDefaultBufferSize(w, h)
                        onVideoSizeChanged?.invoke(w, h)
                    }
                    try {
                        player.start()
                        setStatus(SourceStatus.AKTIF)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Gagal memulai pemutaran overlay video: ${t.message}")
                        setStatus(SourceStatus.GALAT)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer galat what=$what extra=$extra")
                    setStatus(SourceStatus.GALAT)
                    true
                }
                prepareAsync()
            }
            surface = s
            mediaPlayer = mp
        } catch (t: Throwable) {
            Log.e(TAG, "Gagal membuka overlay video $uriString", t)
            lepasPlayer()
            setStatus(SourceStatus.GALAT)
        }
    }

    private fun lepasPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }
        mediaPlayer = null
        try {
            surface?.release()
        } catch (_: Throwable) {
        }
        surface = null
    }

    override fun stop() {
        synchronized(lock) {
            started = false
            lepasPlayer()
            setStatus(SourceStatus.SIAGA)
        }
    }

    private fun setStatus(nilai: SourceStatus) {
        if (status == nilai) return
        status = nilai
        statusListener?.onStatusChanged(this, nilai)
    }

    companion object {
        private const val TAG = "SP-VideoOverlay"
    }
}
