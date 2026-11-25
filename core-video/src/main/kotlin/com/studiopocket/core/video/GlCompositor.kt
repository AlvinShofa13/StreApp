package com.studiopocket.core.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * Kompositor OpenGL ES 3.0 multi-layer.
 * Menggabungkan beberapa sumber video (OES SurfaceTexture & procedural shader) ke satu/beberapa EGLSurface
 * (seperti Surface Preview dan Input Surface Encoder secara bersamaan).
 */
class GlCompositor(
    val canvasWidth: Int = 1280,
    val canvasHeight: Int = 720,
) {
    var onFrameRendered: (() -> Unit)? = null

    private val thread = HandlerThread("SP-GlCompositorThread")
    private lateinit var handler: Handler

    private var eglCore: EglCore? = null
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewViewportW = canvasWidth
    private var previewViewportH = canvasHeight

    private var textureProgram: TextureProgram? = null
    private var colorBarProgram: ColorBarProgram? = null

    private val layers = ConcurrentHashMap<String, CompositorLayer>()
    private var isRunning = false
    private var startTimeMs = SystemClock.elapsedRealtime()
    private var startNanos = SystemClock.elapsedRealtimeNanos()

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            renderFrame()
            // ~30 fps tick = 33ms
            handler.postDelayed(this, 33L)
        }
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
                startTimeMs = SystemClock.elapsedRealtime()
                startNanos = SystemClock.elapsedRealtimeNanos()
                Log.i(TAG, "GlCompositor EGL initialized ($canvasWidth x $canvasHeight)")
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal inisialisasi EGL di GlCompositor", t)
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
                    previewEglSurface = core.createWindowSurface(surface)
                    core.makeCurrent(previewEglSurface)
                    inisiatisasiPrograms()
                    Log.i(TAG, "Output surface preview terpasang ($previewViewportW x $previewViewportH)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Gagal membuat preview window surface", t)
                }
            }
        }
    }

    fun setEncoderSurface(surface: Surface?) {
        handler.post {
            val core = eglCore ?: return@post
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                core.destroySurface(encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }

            if (surface != null && surface.isValid) {
                try {
                    encoderEglSurface = core.createWindowSurface(surface)
                    core.makeCurrent(encoderEglSurface)
                    inisiatisasiPrograms()
                    Log.i(TAG, "Input surface encoder terpasang ($canvasWidth x $canvasHeight)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Gagal membuat encoder window surface", t)
                }
            }
        }
    }

    private fun inisiatisasiPrograms() {
        if (textureProgram == null) {
            textureProgram = TextureProgram()
        }
        if (colorBarProgram == null) {
            colorBarProgram = ColorBarProgram()
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
        onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    ) {
        handler.post {
            val core = eglCore ?: return@post
            val activeSurface = if (previewEglSurface != EGL14.EGL_NO_SURFACE) previewEglSurface else encoderEglSurface
            if (activeSurface != EGL14.EGL_NO_SURFACE) {
                core.makeCurrent(activeSurface)
            }

            val textureId = GlShader.createOesTexture()
            val surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(canvasWidth, canvasHeight)
            }

            val layer = CompositorLayer(
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
            )
            layers[id] = layer
            Log.i(TAG, "Layer OES ditambahkan: $id (textureId=$textureId)")
            onSurfaceTextureReady(surfaceTexture)
        }
    }

    fun addColorBarLayer(id: String, rect: LayerRect, zIndex: Int = 0) {
        handler.post {
            val layer = CompositorLayer(
                id = id,
                type = LayerType.COLOR_BARS,
                rect = rect,
                zIndex = zIndex,
            )
            layers[id] = layer
            Log.i(TAG, "Layer ColorBars ditambahkan: $id")
        }
    }

    fun updateLayerRect(id: String, rect: LayerRect) {
        layers[id]?.rect = rect
    }

    fun updateLayerZIndex(id: String, zIndex: Int) {
        layers[id]?.zIndex = zIndex
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
            layer.surfaceTexture?.release()
            if (layer.textureId != 0) {
                val textures = intArrayOf(layer.textureId)
                GLES30.glDeleteTextures(1, textures, 0)
            }
            Log.i(TAG, "Layer dihapus: $id")
        }
    }

    private fun renderFrame() {
        val core = eglCore ?: return
        if (previewEglSurface == EGL14.EGL_NO_SURFACE && encoderEglSurface == EGL14.EGL_NO_SURFACE) return

        val timeSec = (SystemClock.elapsedRealtime() - startTimeMs) / 1000f
        val ptsNs = SystemClock.elapsedRealtimeNanos() - startNanos

        // Update SurfaceTextures sekali per frame
        val sortedLayers = layers.values.filter { it.isVisible }.sortedBy { it.zIndex }
        for (layer in sortedLayers) {
            if (layer.type == LayerType.OES_TEXTURE) {
                val st = layer.surfaceTexture
                if (st != null) {
                    try {
                        st.updateTexImage()
                        st.getTransformMatrix(layer.stMatrix)
                        applyTransformAdjustments(layer)
                    } catch (t: Throwable) {
                        // Belum ada frame baru
                    }
                }
            }
        }

        // 1. Render ke Surface Preview (jika terpasang)
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(previewEglSurface)
            GLES30.glViewport(0, 0, previewViewportW, previewViewportH)
            drawLayers(sortedLayers, timeSec)
            core.swapBuffers(previewEglSurface)
        }

        // 2. Render ke Surface Encoder (jika perekaman aktif)
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(encoderEglSurface)
            GLES30.glViewport(0, 0, canvasWidth, canvasHeight)
            drawLayers(sortedLayers, timeSec)
            EGLExt.eglPresentationTimeANDROID(core.eglDisplay, encoderEglSurface, ptsNs)
            core.swapBuffers(encoderEglSurface)
        }

        onFrameRendered?.invoke()
    }

    private fun drawLayers(sortedLayers: List<CompositorLayer>, timeSec: Float) {
        GLES30.glClearColor(0.12f, 0.16f, 0.20f, 1.0f) // #1F2933 latar
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        for (layer in sortedLayers) {
            val ndcData = layer.rect.toNdc(canvasWidth.toFloat(), canvasHeight.toFloat())
            when (layer.type) {
                LayerType.OES_TEXTURE -> {
                    textureProgram?.draw(layer.textureId, layer.stMatrix, ndcData)
                }
                LayerType.COLOR_BARS -> {
                    colorBarProgram?.draw(timeSec, ndcData)
                }
            }
        }
    }

    private fun applyTransformAdjustments(layer: CompositorLayer) {
        val matrix = layer.stMatrix

        // Pindah titik acuan ke pusat tekstur (0.5, 0.5)
        Matrix.translateM(matrix, 0, 0.5f, 0.5f, 0f)

        // Rotasi untuk mengoreksi orientasi sensor kamera
        if (layer.rotationDegrees != 0) {
            Matrix.rotateM(matrix, 0, layer.rotationDegrees.toFloat(), 0f, 0f, 1f)
        }

        // Cermin horizontal jika kamera depan
        if (layer.isMirrored) {
            Matrix.scaleM(matrix, 0, -1f, 1f, 1f)
        }

        // Koreksi Center-Crop agar rasio aspek gambar tetap proporsional
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

        // Kembalikan titik acuan dari (0.5, 0.5)
        Matrix.translateM(matrix, 0, -0.5f, -0.5f, 0f)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(renderRunnable)
        handler.post {
            textureProgram?.release()
            colorBarProgram?.release()
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
