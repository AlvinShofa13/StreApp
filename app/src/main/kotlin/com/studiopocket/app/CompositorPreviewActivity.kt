package com.studiopocket.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Environment
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.studiopocket.app.ui.theme.StreAppTheme
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
 * Layar Studio Kompositor GL: Pratinjau & Kontrol Komposisi 3 Sumber Video
 * (Capture Card UVC + Kamera Depan PIP + Pola Uji GL)
 */
class CompositorPreviewActivity : ComponentActivity() {

    private val stats = DebugStats()
    private lateinit var compositor: GlCompositor
    private lateinit var uvcSource: UvcSource
    private lateinit var cameraSource: CameraSource
    private lateinit var testPatternSource: TestPatternSource

    private var recordingPipeline: RecordingPipeline? = null
    private var debugOverlay: DebugOverlay? = null

    private var isRecordingState by mutableStateOf(false)
    private var statusMessageState by mutableStateOf("Menginisialisasi...")
    private var pipPositionIndex by mutableStateOf(0) // 0: Kanan Bawah, 1: Kiri Bawah, 2: Kanan Atas, 3: Kiri Atas

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            mulaiSumber()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Inisialisasi Kompositor GL
        compositor = GlCompositor(canvasWidth = 1280, canvasHeight = 720)
        compositor.onFrameRendered = {
            stats.noteFrameIn("compositor")
            stats.noteFrameOut("compositor")
        }
        stats.registerSource("compositor")

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

        // Layer 0: UVC
        compositor.addOesLayer(
            id = uvcSource.id,
            rect = LayerRect(0f, 0f, 1280f, 720f),
            zIndex = 0,
        ) { texture -> uvcSource.setPreview(texture) }

        // Layer 1: Kamera PIP
        val cameraRot = cameraSource.getGlRotation(getDisplayRotationDegrees())
        compositor.addOesLayer(
            id = cameraSource.id,
            rect = getPipRect(pipPositionIndex),
            zIndex = 1,
            rotationDegrees = cameraRot,
            isMirrored = cameraSource.isFrontFacing,
            sourceWidth = cameraSource.previewWidth,
            sourceHeight = cameraSource.previewHeight,
        ) { texture -> cameraSource.setPreview(texture) }

        val rootFrameLayout = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                StreAppTheme {
                    CompositorScreen(
                        statusMessage = statusMessageState,
                        isRecording = isRecordingState,
                        onBack = { finish() },
                        onToggleRecording = { togglePerekaman() },
                        onChangePipPosition = {
                            pipPositionIndex = (pipPositionIndex + 1) % 4
                            compositor.updateLayerRect(cameraSource.id, getPipRect(pipPositionIndex))
                        },
                        onToggleDebugOverlay = {
                            if (debugOverlay == null) {
                                debugOverlay = DebugOverlay(rootFrameLayout, stats)
                            }
                            debugOverlay?.toggle()
                        },
                        onBindPreviewSurface = { surface, w, h ->
                            compositor.setOutputSurface(surface, w, h)
                        },
                        onUnbindPreviewSurface = {
                            compositor.setOutputSurface(null, 0, 0)
                        },
                    )
                }
            }
        }

        rootFrameLayout.addView(composeView)
        setContentView(rootFrameLayout)

        mintaIzinSistem()
    }

    private fun getPipRect(index: Int): LayerRect {
        return when (index) {
            0 -> LayerRect(860f, 440f, 380f, 240f) // Kanan Bawah
            1 -> LayerRect(40f, 440f, 380f, 240f)  // Kiri Bawah
            2 -> LayerRect(860f, 40f, 380f, 240f)  // Kanan Atas
            else -> LayerRect(40f, 40f, 380f, 240f) // Kiri Atas
        }
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

        val daftar = mutableListOf<String>()
        if (butuhKamera) daftar.add(Manifest.permission.CAMERA)
        if (butuhAudio) daftar.add(Manifest.permission.RECORD_AUDIO)

        if (daftar.isNotEmpty()) {
            requestPermissionLauncher.launch(daftar.toTypedArray())
        } else {
            mulaiSumber()
        }
    }

    private fun mulaiSumber() {
        try {
            uvcSource.start()
            cameraSource.start()
            testPatternSource.start()
            statusMessageState = getString(R.string.compositor_status_active)
        } catch (t: Throwable) {
            statusMessageState = getString(R.string.uvc_status_error)
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
                isRecordingState = true
                statusMessageState = getString(R.string.recording_active, file.name)
            } catch (t: Throwable) {
                statusMessageState = getString(R.string.uvc_status_error)
            }
        } else {
            try {
                val files = currentPipeline.stop()
                recordingPipeline = null
                isRecordingState = false
                val totalSizeKb = files.sumOf { it.length() } / 1024
                val lastFileName = files.lastOrNull()?.name ?: ""
                statusMessageState = getString(R.string.recording_saved, lastFileName, totalSizeKb)
            } catch (t: Throwable) {
                statusMessageState = getString(R.string.uvc_status_error)
            }
        }
    }

    override fun onDestroy() {
        recordingPipeline?.stop()
        if (::uvcSource.isInitialized) uvcSource.stop()
        if (::cameraSource.isInitialized) cameraSource.stop()
        if (::testPatternSource.isInitialized) testPatternSource.stop()
        if (::compositor.isInitialized) compositor.stop()
        debugOverlay?.stop()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompositorScreen(
    statusMessage: String,
    isRecording: Boolean,
    onBack: () -> Unit,
    onToggleRecording: () -> Unit,
    onChangePipPosition: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onBindPreviewSurface: (Surface, Int, Int) -> Unit,
    onUnbindPreviewSurface: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Studio Kompositor GL",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2933),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2933))
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF3D6FA5).copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "3 Sumber Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF3D6FA5),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF6F7F9)),
            )
        },
        containerColor = Color(0xFFF6F7F9),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 16:9 Canvas Preview Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2933)),
                elevation = CardDefaults.cardElevation(4.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                        onBindPreviewSurface(Surface(texture), width, height)
                                    }

                                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                                        onBindPreviewSurface(Surface(texture), width, height)
                                    }

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        onUnbindPreviewSurface()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E6EA)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color by animateColorAsState(
                        targetValue = if (isRecording) Color(0xFFC0504D) else Color(0xFF4F8A6B),
                        label = "color",
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color, shape = CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2933),
                    )
                }
            }

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onChangePipPosition,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compositor_pip_position), fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onToggleDebugOverlay,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.debug_overlay_toggle), fontSize = 12.sp)
                }
            }

            // Primary Record Button
            Button(
                onClick = onToggleRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFC0504D) else Color(0xFF3D6FA5),
                ),
            ) {
                Text(
                    text = if (isRecording) stringResource(R.string.recording_stop) else stringResource(R.string.recording_start),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
