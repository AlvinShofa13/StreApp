package com.studiopocket.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Environment
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.studiopocket.core.video.GlCompositor
import com.studiopocket.core.video.LayerRect
import com.studiopocket.core.video.RecordingPipeline
import com.studiopocket.core.video.TestPatternSource
import com.studiopocket.debug.DebugOverlay
import com.studiopocket.debug.DebugStats
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus
import com.studiopocket.source.camera.CameraSource
import com.studiopocket.source.uvc.UvcSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Layar pengujian T1.2 & T1.3: Kompositor OpenGL dengan 3 sumber video simultan
 * serta perekaman langsung ke file MP4 (Video H.264 + Audio AAC Mic).
 */
class CompositorPreviewActivity : Activity() {

    private val stats = DebugStats()
    private lateinit var compositor: GlCompositor
    private lateinit var uvcSource: UvcSource
    private lateinit var cameraSource: CameraSource
    private lateinit var testPatternSource: TestPatternSource

    private var recordingPipeline: RecordingPipeline? = null

    private lateinit var statusView: TextView
    private lateinit var recordButton: Button
    private lateinit var debugOverlay: DebugOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.catat("CompositorPreviewActivity dibuat")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF6F7F9.toInt()) }
        val kolom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        statusView = TextView(this).apply {
            setTextColor(0xFF5B6670.toInt())
            textSize = 14f
            text = getString(R.string.compositor_status_initializing)
        }

        val preview = CompositorTextureView(this)
        val previewBox = FrameLayout(this).apply {
            setBackgroundColor(0xFF1F2933.toInt())
            addView(
                preview,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        recordButton = Button(this).apply {
            setText(R.string.recording_start)
            setOnClickListener { togglePerekaman() }
        }

        kolom.addView(statusView)
        kolom.addView(
            previewBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        kolom.addView(recordButton)
        kolom.addView(Button(this).apply {
            setText(R.string.debug_overlay_toggle)
            setOnClickListener { debugOverlay.toggle() }
        })

        root.addView(kolom)
        setContentView(root)

        debugOverlay = DebugOverlay(root, stats)

        // Inisialisasi Kompositor GL
        compositor = GlCompositor(canvasWidth = 1280, canvasHeight = 720)
        compositor.onFrameRendered = {
            stats.noteFrameIn("compositor")
            stats.noteFrameOut("compositor")
        }
        stats.registerSource("compositor")

        // Inisialisasi Tiga Sumber
        uvcSource = UvcSource(this, getString(R.string.uvc_source_name))
        cameraSource = CameraSource(this, getString(R.string.camera_source_name), useFrontFacing = true)
        testPatternSource = TestPatternSource(getString(R.string.test_pattern_source_name))

        stats.registerSource(uvcSource.name)
        stats.registerSource(cameraSource.name)
        stats.registerSource(testPatternSource.name)

        cameraSource.statusListener = object : Source.StatusListener {
            override fun onStatusChanged(source: Source, status: SourceStatus) {
                if (status == SourceStatus.AKTIF) {
                    val rot = cameraSource.getGlRotation(getDisplayRotationDegrees())
                    compositor.updateLayerTransform(
                        id = cameraSource.id,
                        rotationDegrees = rot,
                        isMirrored = cameraSource.isFrontFacing,
                        sourceWidth = cameraSource.previewWidth,
                        sourceHeight = cameraSource.previewHeight,
                    )
                }
            }
        }

        compositor.start()

        // Tambah Layer 1: UVC Capture Card (Latar belakang / Full Canvas)
        compositor.addOesLayer(
            id = uvcSource.id,
            rect = LayerRect(0f, 0f, 1280f, 720f),
            zIndex = 0,
        ) { texture ->
            uvcSource.setPreview(texture)
        }

        val cameraRot = cameraSource.getGlRotation(getDisplayRotationDegrees())

        // Tambah Layer 2: Kamera Depan (Overlay Kanan Bawah)
        compositor.addOesLayer(
            id = cameraSource.id,
            rect = LayerRect(860f, 440f, 380f, 240f),
            zIndex = 1,
            rotationDegrees = cameraRot,
            isMirrored = cameraSource.isFrontFacing,
            sourceWidth = cameraSource.previewWidth,
            sourceHeight = cameraSource.previewHeight,
        ) { texture ->
            cameraSource.setPreview(texture)
        }

        // Tambah Layer 3: Pola Uji (Overlay Kiri Atas)
        compositor.addColorBarLayer(
            id = testPatternSource.id,
            rect = LayerRect(40f, 40f, 320f, 180f),
            zIndex = 2,
        )

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(texture)
                compositor.setOutputSurface(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(texture)
                compositor.setOutputSurface(surface, width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                compositor.setOutputSurface(null, 0, 0)
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }

        debugOverlay.toggle()

        mintaIzinSistem()
    }

    private fun getDisplayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun mintaIzinSistem() {
        val butuhKamera = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val butuhAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

        val daftarIzin = mutableListOf<String>()
        if (butuhKamera) daftarIzin.add(Manifest.permission.CAMERA)
        if (butuhAudio) daftarIzin.add(Manifest.permission.RECORD_AUDIO)

        if (daftarIzin.isNotEmpty()) {
            requestPermissions(daftarIzin.toTypedArray(), REQ_IZIN)
        } else {
            mulaiSumber()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_IZIN) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            mulaiSumber()
        } else {
            statusView.setText(R.string.uvc_status_no_permission)
        }
    }

    private fun mulaiSumber() {
        Diag.catat("Memulai 3 sumber kompositor")
        try {
            uvcSource.start()
            cameraSource.start()
            testPatternSource.start()
            statusView.text = getString(R.string.compositor_status_active)
        } catch (t: Throwable) {
            Diag.catat("Gagal mulai sumber kompositor", t)
            statusView.text = getString(R.string.uvc_status_error)
        }
    }

    private fun togglePerekaman() {
        val currentPipeline = recordingPipeline
        if (currentPipeline == null || !currentPipeline.isRecording) {
            val fileDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(fileDir, "StreApp_$timestamp.mp4")

            try {
                val pipeline = RecordingPipeline(this, compositor, file)
                pipeline.start()
                recordingPipeline = pipeline
                recordButton.setText(R.string.recording_stop)
                statusView.text = getString(R.string.recording_active, file.name)
                Diag.catat("Perekaman MP4 dimulai: ${file.name}")
            } catch (t: Throwable) {
                Diag.catat("Gagal memulai perekaman MP4", t)
                statusView.text = getString(R.string.uvc_status_error)
            }
        } else {
            try {
                val files = currentPipeline.stop()
                recordingPipeline = null
                recordButton.setText(R.string.recording_start)
                val totalSizeKb = files.sumOf { it.length() } / 1024
                val lastFileName = files.lastOrNull()?.name ?: ""
                statusView.text = getString(R.string.recording_saved, lastFileName, totalSizeKb)
                Diag.catat("Perekaman MP4 dihentikan: $lastFileName ($totalSizeKb KB, ${files.size} segmen)")
            } catch (t: Throwable) {
                Diag.catat("Gagal menghentikan perekaman MP4", t)
            }
        }
    }

    override fun onDestroy() {
        Diag.catat("CompositorPreviewActivity ditutup")
        recordingPipeline?.stop()
        if (::uvcSource.isInitialized) uvcSource.stop()
        if (::cameraSource.isInitialized) cameraSource.stop()
        if (::testPatternSource.isInitialized) testPatternSource.stop()
        if (::compositor.isInitialized) compositor.stop()
        debugOverlay.stop()
        super.onDestroy()
    }

    private companion object {
        const val REQ_IZIN = 3
    }
}

/** TextureView 16:9 untuk preview output kompositor GL. */
private class CompositorTextureView(context: Context) : TextureView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lebar = MeasureSpec.getSize(widthMeasureSpec)
        val tinggi = lebar * 9 / 16
        val batas = MeasureSpec.getSize(heightMeasureSpec)
        val tinggiAkhir = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            tinggi
        } else {
            tinggi.coerceAtMost(batas)
        }
        setMeasuredDimension(lebar, tinggiAkhir)
    }
}
