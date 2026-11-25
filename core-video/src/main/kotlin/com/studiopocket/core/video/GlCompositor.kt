package com.studiopocket.core.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Kompositor OpenGL ES 3.0 multi-layer.
 *
 * Menggabungkan sumber video (OES SurfaceTexture), pola uji, serta overlay bitmap ke
 * surface preview dan surface encoder sekaligus. Tidak ada frame yang disalin ke CPU.
 * Fps preview dan encoder dapat diturunkan oleh kebijakan termal.
 */
class GlCompositor(
    canvasWidth: Int = 1280,
    canvasHeight: Int = 720,
) {
    @Volatile
    var canvasWidth: Int = canvasWidth
        private set

    @Volatile
    var canvasHeight: Int = canvasHeight
        private set

    var onFrameRendered: (() -> Unit)? = null

    @Volatile
    var previewEnabled: Boolean = true

    @Volatile
    var previewFps: Int = 30

    @Volatile
    var encoderFps: Int = 30

    private val thread = HandlerThread("SP-GlCompositorThread")
    private lateinit var handler: Handler

    private var eglCore: EglCore? = null
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewViewportW = canvasWidth
    private var previewViewportH = canvasHeight

    private var textureProgram: TextureProgram? = null
    private var colorBarProgram: ColorBarProgram? = null
    private var bitmapProgram: BitmapProgram? = null
    private var solidProgram: SolidProgram? = null

    private val layers = ConcurrentHashMap<String, CompositorLayer>()
    private var isRunning = false
    private var startTimeMs = SystemClock.elapsedRealtime()
    private var encoderBaseNs = 0L
    private var nextPreviewNs = 0L
    private var nextEncoderNs = 0L

    @Volatile
    private var fadeStartNs = 0L

    @Volatile
    private var fadeDurationNs = 0L

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val now = SystemClock.elapsedRealtimeNanos()
            var berikut = now + 100_000_000L
            val core = eglCore
            val adaPreview = previewEnabled && previewEglSurface != EGL14.EGL_NO_SURFACE
            val adaEncoder = encoderEglSurface != EGL14.EGL_NO_SURFACE
            if (core != null && (adaPreview || adaEncoder)) {
                val previewIntervalNs = 1_000_000_000L / previewFps.coerceIn(1, 30)
                val encoderIntervalNs = 1_000_000_000L / encoderFps.coerceIn(1, 60)

                val duePreview = adaPreview && now >= nextPreviewNs
                val dueEncoder = adaEncoder && now >= nextEncoderNs

                if (duePreview || dueEncoder) {
                    perbaruiTekstur()
                }
                if (duePreview) {
                    renderKe(core, previewEglSurface, previewViewportW, previewViewportH)
                    nextPreviewNs = if (nextPreviewNs == 0L || now - nextPreviewNs > previewIntervalNs * 2) {
                        now + previewIntervalNs
                    } else {
                        nextPreviewNs + previewIntervalNs
                    }
                }
                if (dueEncoder) {
                    renderKe(core, encoderEglSurface, canvasWidth, canvasHeight)
                    nextEncoderNs = if (nextEncoderNs == 0L || now - nextEncoderNs > encoderIntervalNs * 2) {
                        now + encoderIntervalNs
                    } else {
                        nextEncoderNs + encoderIntervalNs
                    }
                }
                if (adaPreview) berikut = min(berikut, nextPreviewNs)
                if (adaEncoder) berikut = min(berikut, nextEncoderNs)
                if (duePreview || dueEncoder) onFrameRendered?.invoke()
            }
            val sisaNs = berikut - SystemClock.elapsedRealtimeNanos()
            val jedaMs = if (sisaNs <= 0L) 1L else max(1L, (sisaNs + 999_999L) / 1_000_000L)
            handler.postDelayed(this, jedaMs)
        }
    }

    /** Ubah ukuran kanvas output tanpa membongkar EGL context atau SurfaceTexture sumber. */
    fun resizeCanvas(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        canvasWidth = width
        canvasHeight = height
        Log.i(TAG, "Ukuran kanvas diubah: $width x $height")
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try {
                val core = EglCore()
                eglCore = core
                core.makeCurrentOffscreen()
                inisialisasiPrograms()
                startTimeMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "GlCompositor EGL siap ($canvasWidth x $canvasHeight)")
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal inisialisasi EGL", t)
            }
        }
        handler.post(renderRunnable)
    }

    fun setOutputSurface(surface: Surface?, width: Int, height: Int) {
        handler.post {
            val core = eglCore ?: return@post
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                core.destroySurface(previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            previewViewportW = if (width > 0) width else canvasWidth
            previewViewportH = if (height > 0) height else canvasHeight
            if (surface != null && surface.isValid) {
                try {
                    core.makeCurrentOffscreen()
                    previewEglSurface = core.createWindowSurface(surface)
                    core.makeCurrent(previewEglSurface)
                    nextPreviewNs = SystemClock.elapsedRealtimeNanos()
                    Log.i(TAG, "Surface preview terpasang ($previewViewportW x $previewViewportH)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Gagal membuat preview window surface", t)
                }
            }
        }
    }

    fun setEncoderSurface(surface: Surface?, timeBaseNs: Long = 0L) {
        handler.post {
            val core = eglCore ?: return@post
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                core.destroySurface(encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                try {
                    core.makeCurrentOffscreen()
                    encoderEglSurface = core.createWindowSurface(surface)
                    core.makeCurrent(encoderEglSurface)
                    encoderBaseNs = if (timeBaseNs > 0L) timeBaseNs else SystemClock.elapsedRealtimeNanos()
                    nextEncoderNs = SystemClock.elapsedRealtimeNanos()
                    Log.i(TAG, "Surface encoder terpasang ($canvasWidth x $canvasHeight)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Gagal membuat encoder window surface", t)
                }
            }
        }
    }

    private fun inisialisasiPrograms() {
        if (textureProgram == null) textureProgram = TextureProgram()
        if (colorBarProgram == null) colorBarProgram = ColorBarProgram()
        if (bitmapProgram == null) bitmapProgram = BitmapProgram()
        if (solidProgram == null) solidProgram = SolidProgram()
    }

    private fun makeCurrentAny() {
        val core = eglCore ?: return
        when {
            previewEglSurface != EGL14.EGL_NO_SURFACE -> core.makeCurrent(previewEglSurface)
            encoderEglSurface != EGL14.EGL_NO_SURFACE -> core.makeCurrent(encoderEglSurface)
            else -> core.makeCurrentOffscreen()
        }
    }

    fun addOesLayer(
        id: String,
        rect: LayerRect,
        zIndex: Int = 0,
        sourceWidth: Int = 1280,
        sourceHeight: Int = 720,
        rotationDegrees: Int = 0,
        isMirrored: Boolean = false,
        shape: LayerShape = LayerShape.RECT,
        cornerRadius: Float = 0.12f,
        onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    ) {
        handler.post {
            if (eglCore == null) {
                Log.e(TAG, "Layer OES $id diminta sebelum EGL siap")
                return@post
            }
            makeCurrentAny()
            val textureId = GlShader.createOesTexture()
            val surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(sourceWidth, sourceHeight)
            }
            layers[id] = CompositorLayer(
                id = id,
                type = LayerType.OES_TEXTURE,
                rect = rect,
                zIndex = zIndex,
                textureId = textureId,
                surfaceTexture = surfaceTexture,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotationDegrees = rotationDegrees,
                isMirrored = isMirrored,
                shape = shape,
                cornerRadius = cornerRadius,
            )
            Log.i(TAG, "Layer OES ditambahkan: $id")
            onSurfaceTextureReady(surfaceTexture)
        }
    }

    fun addColorBarLayer(
        id: String,
        rect: LayerRect,
        zIndex: Int = 0,
        shape: LayerShape = LayerShape.RECT,
    ) {
        handler.post {
            layers[id] = CompositorLayer(
                id = id,
                type = LayerType.COLOR_BARS,
                rect = rect,
                zIndex = zIndex,
                shape = shape,
            )
            Log.i(TAG, "Layer pola uji ditambahkan: $id")
        }
    }

    /** Bitmap diunggah sekali; jangan panggil per frame. */
    fun addBitmapLayer(
        id: String,
        bitmap: Bitmap,
        rect: LayerRect,
        zIndex: Int = 0,
        shape: LayerShape = LayerShape.RECT,
        cornerRadius: Float = 0.12f,
    ) {
        handler.post {
            makeCurrentAny()
            val textureId = unggahBitmap(bitmap)
            layers[id] = CompositorLayer(
                id = id,
                type = LayerType.BITMAP,
                rect = rect,
                zIndex = zIndex,
                textureId = textureId,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                shape = shape,
                cornerRadius = cornerRadius,
            )
            Log.i(TAG, "Layer bitmap ditambahkan: $id (${bitmap.width}x${bitmap.height})")
        }
    }

    fun updateBitmapLayer(id: String, bitmap: Bitmap) {
        handler.post {
            val layer = layers[id] ?: return@post
            makeCurrentAny()
            val textures = intArrayOf(layer.textureId)
            GLES30.glDeleteTextures(1, textures, 0)
            layer.textureId = unggahBitmap(bitmap)
            layer.sourceWidth = bitmap.width
            layer.sourceHeight = bitmap.height
        }
    }

    private fun unggahBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun updateLayerRect(id: String, rect: LayerRect) {
        layers[id]?.rect = rect
    }

    fun updateLayerZIndex(id: String, zIndex: Int) {
        layers[id]?.zIndex = zIndex
    }

    fun updateLayerShape(id: String, shape: LayerShape, cornerRadius: Float = 0.12f) {
        layers[id]?.let {
            it.shape = shape
            it.cornerRadius = cornerRadius
        }
    }

    fun updateLayerTransform(
        id: String,
        rotationDegrees: Int = 0,
        isMirrored: Boolean = false,
        sourceWidth: Int = 1280,
        sourceHeight: Int = 720,
    ) {
        val layer = layers[id] ?: return
        layer.rotationDegrees = rotationDegrees
        layer.isMirrored = isMirrored
        if (sourceWidth > 0) layer.sourceWidth = sourceWidth
        if (sourceHeight > 0) layer.sourceHeight = sourceHeight
    }

    fun setLayerVisible(id: String, visible: Boolean) {
        layers[id]?.isVisible = visible
    }

    fun removeLayer(id: String) {
        handler.post {
            val layer = layers.remove(id) ?: return@post
            makeCurrentAny()
            layer.surfaceTexture?.release()
            if (layer.textureId != 0) {
                val textures = intArrayOf(layer.textureId)
                GLES30.glDeleteTextures(1, textures, 0)
            }
            Log.i(TAG, "Layer dihapus: $id")
        }
    }

    /** Fade ke hitam lalu kembali; engine mengganti layer pada titik tengah. */
    fun startFade(durationMs: Int) {
        fadeStartNs = SystemClock.elapsedRealtimeNanos()
        fadeDurationNs = durationMs.coerceAtLeast(0) * 1_000_000L
    }

    private fun perbaruiTekstur() {
        for (layer in layers.values) {
            if (!layer.isVisible || layer.type != LayerType.OES_TEXTURE) continue
            val st = layer.surfaceTexture ?: continue
            try {
                st.updateTexImage()
                st.getTransformMatrix(layer.stMatrix)
                applyTransformAdjustments(layer)
            } catch (_: Throwable) {
                // Sumber belum mengirim frame baru.
            }
        }
    }

    private fun renderKe(core: EglCore, surface: EGLSurface, lebar: Int, tinggi: Int) {
        core.makeCurrent(surface)
        GLES30.glViewport(0, 0, lebar, tinggi)
        val waktu = (SystemClock.elapsedRealtime() - startTimeMs) / 1000f
        drawLayers(waktu)
        if (surface == encoderEglSurface) {
            val pts = SystemClock.elapsedRealtimeNanos() - encoderBaseNs
            EGLExt.eglPresentationTimeANDROID(core.eglDisplay, surface, pts)
        }
        if (!core.swapBuffers(surface)) {
            Log.w(TAG, "eglSwapBuffers gagal")
        }
    }

    private fun drawLayers(timeSec: Float) {
        GLES30.glClearColor(0.12f, 0.16f, 0.20f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val urut = layers.values.filter { it.isVisible }.sortedBy { it.zIndex }
        for (layer in urut) {
            val ndc = layer.rect.toNdc(canvasWidth.toFloat(), canvasHeight.toFloat())
            val aspect = if (layer.rect.height > 0f) layer.rect.width / layer.rect.height else 1f
            val bentuk = layer.shape.ordinal
            when (layer.type) {
                LayerType.OES_TEXTURE -> textureProgram?.draw(
                    layer.textureId, layer.stMatrix, ndc, bentuk, layer.cornerRadius, aspect,
                )

                LayerType.BITMAP -> bitmapProgram?.draw(
                    layer.textureId, ndc, bentuk, layer.cornerRadius, aspect,
                )

                LayerType.COLOR_BARS -> colorBarProgram?.draw(timeSec, ndc)
            }
        }

        val durasi = fadeDurationNs
        if (durasi > 0L) {
            val lewat = SystemClock.elapsedRealtimeNanos() - fadeStartNs
            if (lewat >= durasi) {
                fadeDurationNs = 0L
            } else {
                val setengah = durasi / 2
                val alfa = if (lewat < setengah) {
                    lewat.toFloat() / setengah
                } else {
                    1f - (lewat - setengah).toFloat() / setengah
                }
                solidProgram?.draw(alfa)
            }
        }
    }

    private fun applyTransformAdjustments(layer: CompositorLayer) {
        val matrix = layer.stMatrix
        Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)
        if (layer.rotationDegrees != 0) {
            Matrix.rotateM(matrix, 0, layer.rotationDegrees.toFloat(), 0f, 0f, 1f)
        }
        if (layer.isMirrored) {
            Matrix.scaleM(matrix, 0, -1f, 1f, 1f)
        }
        val effW: Float
        val effH: Float
        if (layer.rotationDegrees == 90 || layer.rotationDegrees == 270) {
            effW = layer.sourceHeight.toFloat()
            effH = layer.sourceWidth.toFloat()
        } else {
            effW = layer.sourceWidth.toFloat()
            effH = layer.sourceHeight.toFloat()
        }
        if (effW > 0f && effH > 0f && layer.rect.width > 0f && layer.rect.height > 0f) {
            val srcAspect = effW / effH
            val dstAspect = layer.rect.width / layer.rect.height
            var scaleX = 1f
            var scaleY = 1f
            if (srcAspect > dstAspect) {
                scaleX = dstAspect / srcAspect
            } else if (srcAspect < dstAspect) {
                scaleY = srcAspect / dstAspect
            }
            Matrix.scaleM(matrix, 0, scaleX, scaleY, 1f)
        }
        Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(renderRunnable)
        handler.post {
            makeCurrentAny()
            textureProgram?.release()
            colorBarProgram?.release()
            bitmapProgram?.release()
            solidProgram?.release()
            for (layer in layers.values) {
                layer.surfaceTexture?.release()
                if (layer.textureId != 0) {
                    val textures = intArrayOf(layer.textureId)
                    GLES30.glDeleteTextures(1, textures, 0)
                }
            }
            layers.clear()
            val core = eglCore
            if (core != null) {
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    core.destroySurface(previewEglSurface)
                    previewEglSurface = EGL14.EGL_NO_SURFACE
                }
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    core.destroySurface(encoderEglSurface)
                    encoderEglSurface = EGL14.EGL_NO_SURFACE
                }
                core.release()
            }
            eglCore = null
            Log.i(TAG, "GlCompositor dihentikan")
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "SP-GlCompositor"
    }
}
