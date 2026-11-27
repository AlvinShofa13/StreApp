package com.studiopocket.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.studiopocket.app.engine.AppEngine
import com.studiopocket.app.engine.EngineState
import com.studiopocket.app.engine.StreamProfileStore
import com.studiopocket.app.ui.components.InteractivePreviewCanvas
import com.studiopocket.app.ui.components.SceneStrip
import com.studiopocket.app.ui.panels.AudioMixerPanel
import com.studiopocket.app.ui.panels.SettingsPanel
import com.studiopocket.app.ui.panels.SourcesPanel
import com.studiopocket.app.ui.panels.StreamChatPanel
import com.studiopocket.app.ui.theme.Aksen
import com.studiopocket.app.ui.theme.Berhasil
import com.studiopocket.app.ui.theme.Garis
import com.studiopocket.app.ui.theme.Kartu
import com.studiopocket.app.ui.theme.Latar
import com.studiopocket.app.ui.theme.Peringatan
import com.studiopocket.app.ui.theme.Rekam
import com.studiopocket.app.ui.theme.StreAppTheme
import com.studiopocket.app.ui.theme.Teks
import com.studiopocket.app.ui.theme.TeksSekunder
import com.studiopocket.core.thermal.ThermalLevel
import com.studiopocket.debug.DebugOverlay
import com.studiopocket.debug.DebugStats
import com.studiopocket.model.SourceType
import com.studiopocket.model.Transform
import java.io.File
import java.util.Locale

private enum class StudioTab(val labelRes: Int, val icon: ImageVector) {
    SOURCES(R.string.tab_sources, Icons.Outlined.Layers),
    MIXER(R.string.tab_mixer, Icons.Outlined.GraphicEq),
    STREAM(R.string.tab_stream, Icons.Outlined.Sensors),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Tune),
}

class MainActivity : ComponentActivity() {

    private lateinit var engine: AppEngine
    private lateinit var profileStore: StreamProfileStore
    private lateinit var chatController: ChatController

    private val debugStats = DebugStats()
    private var debugOverlay: DebugOverlay? = null
    private var pendingExportFile: File? = null

    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        engine.onPermissionsGranted()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        engine.onPermissionsGranted()
        val granted = permissions.entries.all { it.value }
        if (granted) {
            engine.startRecording()
        } else {
            Toast.makeText(this, getString(R.string.uvc_status_no_permission), Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(result.resultCode, data)
            if (projection != null) {
                engine.attachProjection(projection)
            }
        } else {
            Toast.makeText(this, getString(R.string.screen_permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            val id = engine.addSource(
                type = SourceType.IMAGE,
                name = getString(R.string.source_type_image),
                content = uri.toString(),
            )
            if (id != null) {
                engine.setTransform(id, Transform(0.05f, 0.05f, 0.24f, 0.24f))
                engine.selectSource(id)
            }
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            val id = engine.addSource(
                type = SourceType.VIDEO,
                name = getString(R.string.source_type_video),
                content = uri.toString(),
            )
            if (id != null) {
                engine.setTransform(id, Transform(0.05f, 0.05f, 0.36f, 0.28f))
                engine.selectSource(id)
            }
        }
    }

    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destinationUri: Uri? ->
        val sourceFile = pendingExportFile ?: engine.state.value.reviewFile
        if (destinationUri != null && sourceFile != null && sourceFile.exists()) {
            try {
                contentResolver.openOutputStream(destinationUri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, getString(R.string.export_success), Toast.LENGTH_LONG).show()
                engine.clearReviewFile()
            } catch (t: Throwable) {
                Toast.makeText(
                    this,
                    getString(R.string.export_failed, t.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = AppContainer.engine(this)
        profileStore = AppContainer.profiles(this)
        chatController = AppContainer.chat()

        debugStats.registerSource("compositor")
        engine.onCompositorFrame = {
            debugStats.noteFrameIn("compositor")
            debugStats.noteFrameOut("compositor")
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.1"
        } catch (_: Throwable) {
            "1.1.1"
        }

        val rootFrameLayout = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                StreAppTheme {
                    StudioWorkspaceScreen(
                        engine = engine,
                        profileStore = profileStore,
                        chatController = chatController,
                        appVersion = appVersion,
                        onToggleRecording = { toggleRecording() },
                        onRequestScreenCapture = { requestScreenCapture() },
                        onPickImageOverlay = { imagePickerLauncher.launch(arrayOf("image/*")) },
                        onPickVideoOverlay = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        onOpenCameraNodeScanner = {
                            startActivity(Intent(this@MainActivity, CameraNodeActivity::class.java))
                        },
                        onExportVideo = { file ->
                            pendingExportFile = file
                            exportDocumentLauncher.launch(file.name)
                        },
                        onToggleDebugOverlay = {
                            if (debugOverlay == null) {
                                debugOverlay = DebugOverlay(rootFrameLayout, debugStats)
                            }
                            debugOverlay?.toggle()
                        },
                        onSetSystemBarsVisible = { visible ->
                            val controller = WindowCompat.getInsetsController(window, window.decorView)
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (visible) {
                                controller.show(WindowInsetsCompat.Type.systemBars())
                            } else {
                                controller.hide(WindowInsetsCompat.Type.systemBars())
                            }
                        },
                    )
                }
            }
        }

        rootFrameLayout.addView(composeView)
        setContentView(rootFrameLayout)

        // Minta izin CAMERA & RECORD_AUDIO saat awal buka agar capture card USB & audio langsung aktif
        val missingPerms = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missingPerms.isNotEmpty()) {
            startupPermissionLauncher.launch(missingPerms.toTypedArray())
        } else {
            engine.onPermissionsGranted()
        }
    }

    override fun onPause() {
        chatController.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        chatController.resume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            engine.onPermissionsGranted()
        }
    }

    override fun onDestroy() {
        engine.onCompositorFrame = null
        debugOverlay?.stop()
        super.onDestroy()
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun toggleRecording() {
        val state = engine.state.value
        if (state.savingRecording) return
        if (state.recording) {
            engine.stopRecording()
        } else {
            val cameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val audioPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (cameraPermission && audioPermission) {
                engine.ensureAudio()
                engine.startRecording()
            } else {
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioWorkspaceScreen(
    engine: AppEngine,
    profileStore: StreamProfileStore,
    chatController: ChatController,
    appVersion: String,
    onToggleRecording: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onPickImageOverlay: () -> Unit,
    onPickVideoOverlay: () -> Unit,
    onOpenCameraNodeScanner: () -> Unit,
    onExportVideo: (File) -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onSetSystemBarsVisible: (Boolean) -> Unit,
) {
    val state by engine.state.collectAsState()
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var activeTab by rememberSaveable { mutableStateOf(StudioTab.SOURCES) }
    var canvasLocked by rememberSaveable { mutableStateOf(false) }
    var showLandscapeDock by rememberSaveable { mutableStateOf(true) }
    var isPreviewFullscreen by rememberSaveable { mutableStateOf(false) }

    val activeScene = state.project.activeScene
    val sceneName = activeScene?.name ?: stringResource(R.string.scene_default)

    BackHandler(enabled = isPreviewFullscreen) {
        isPreviewFullscreen = false
    }

    LaunchedEffect(isPreviewFullscreen) {
        onSetSystemBarsVisible(!isPreviewFullscreen)
    }

    DisposableEffect(Unit) {
        onDispose { onSetSystemBarsVisible(true) }
    }

    if (isPreviewFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F14)),
        ) {
            InteractivePreviewCanvas(
                sources = activeScene?.sources.orEmpty(),
                selectedSourceId = state.selectedSourceId,
                sceneName = sceneName,
                resolutionLabel = state.recordingResolution.shortLabel,
                outputWidth = state.outputWidth,
                outputHeight = state.outputHeight,
                recording = state.recording,
                recordingMs = state.recordingMs,
                previewEnabled = state.previewEnabled,
                canvasLocked = true,
                isFullscreen = true,
                onToggleLock = {},
                onTogglePreview = { engine.setPreviewEnabled(!state.previewEnabled) },
                onToggleFullscreen = { isPreviewFullscreen = false },
                onToggleRecording = onToggleRecording,
                onSelectSource = {},
                onUpdateTransform = { id, t -> engine.setTransform(id, t) },
                onAttachPreview = { surface, w, h -> engine.attachPreview(surface, w, h) },
                onDetachPreview = { engine.detachPreview() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val storageText = if (state.storageFreeMb >= 1024L) {
                        stringResource(R.string.home_storage_free_gb, state.storageFreeMb / 1024.0)
                    } else {
                        stringResource(R.string.home_storage_free, state.storageFreeMb)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Teks,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = Kartu,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Garis),
                        ) {
                            Text(
                                text = stringResource(R.string.app_version_badge, appVersion),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TeksSekunder,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = storageText,
                            fontSize = 11.sp,
                            color = TeksSekunder,
                        )
                    }
                },
                actions = {
                    // Badge Suhu (PRD 8.3 #1)
                    val thermalColor = when (state.thermal) {
                        ThermalLevel.NORMAL, ThermalLevel.RINGAN -> Berhasil
                        ThermalLevel.SEDANG -> Peringatan
                        else -> Rekam
                    }
                    val thermalText = when (state.thermal) {
                        ThermalLevel.NORMAL, ThermalLevel.RINGAN -> stringResource(R.string.home_thermal_normal)
                        ThermalLevel.SEDANG -> stringResource(R.string.home_thermal_warm)
                        else -> stringResource(R.string.home_thermal_critical)
                    }
                    Surface(
                        color = thermalColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = thermalText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = thermalColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }

                    if (state.batteryPercent > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = Kartu,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                        ) {
                            Text(
                                text = stringResource(R.string.badge_battery, state.batteryPercent),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Teks,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Tombol FPS Debug Overlay (min 48dp touch target)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onToggleDebugOverlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Kartu,
                            border = BorderStroke(1.dp, Garis),
                        ) {
                            Text(
                                text = stringResource(R.string.badge_fps),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Aksen,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            )
                        }
                    }

                    if (isLandscape) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { showLandscapeDock = !showLandscapeDock },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ViewSidebar,
                                contentDescription = stringResource(R.string.canvas_panel_toggle),
                                tint = if (showLandscapeDock) Aksen else TeksSekunder,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Latar),
            )
        },
        containerColor = Latar,
    ) { padding ->
        if (isLandscape) {
            // Mode Lanskap (PRD 8.4): Preview besar di kiri, Dock collapsible di kanan
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(if (showLandscapeDock) 1.15f else 1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ErrorBanner(state.errorRes, state.errorDetail, onDismiss = { engine.consumeError() })

                    InteractivePreviewCanvas(
                        sources = activeScene?.sources.orEmpty(),
                        selectedSourceId = state.selectedSourceId,
                        sceneName = sceneName,
                        resolutionLabel = state.recordingResolution.shortLabel,
                        outputWidth = state.outputWidth,
                        outputHeight = state.outputHeight,
                        recording = state.recording,
                        recordingMs = state.recordingMs,
                        previewEnabled = state.previewEnabled,
                        canvasLocked = canvasLocked,
                        isFullscreen = false,
                        onToggleLock = { canvasLocked = !canvasLocked },
                        onTogglePreview = { engine.setPreviewEnabled(!state.previewEnabled) },
                        onToggleFullscreen = { isPreviewFullscreen = true },
                        onSelectSource = { engine.selectSource(it) },
                        onUpdateTransform = { id, t -> engine.setTransform(id, t) },
                        onAttachPreview = { surface, w, h -> engine.attachPreview(surface, w, h) },
                        onDetachPreview = { engine.detachPreview() },
                    )

                    SceneStrip(
                        scenes = state.project.scenes,
                        activeSceneId = state.project.activeSceneId,
                        onSelectScene = { engine.setActiveScene(it) },
                        onAddScene = { engine.addScene(it) },
                        onRenameScene = { id, name -> engine.renameScene(id, name) },
                        onDuplicateScene = { id, newName -> engine.duplicateScene(id, newName) },
                        onRemoveScene = { engine.removeScene(it) },
                    )

                    PrimaryTransportBar(
                        state = state,
                        onToggleRecording = onToggleRecording,
                        onAddMarker = { engine.addMarker() },
                    )
                }

                if (showLandscapeDock) {
                    Column(
                        modifier = Modifier
                            .weight(0.85f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StudioDockTabBar(activeTab = activeTab, onSelectTab = { activeTab = it })
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            StudioDockContent(
                                tab = activeTab,
                                state = state,
                                engine = engine,
                                profileStore = profileStore,
                                chatController = chatController,
                                appVersion = appVersion,
                                onRequestScreenCapture = onRequestScreenCapture,
                                onPickImageOverlay = onPickImageOverlay,
                                onPickVideoOverlay = onPickVideoOverlay,
                                onOpenCameraNodeScanner = onOpenCameraNodeScanner,
                            )
                        }
                    }
                }
            }
        } else {
            // Mode Potret (PRD 8.4): Pengendalian satu tangan yang lapang dan bersih
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorBanner(state.errorRes, state.errorDetail, onDismiss = { engine.consumeError() })

                InteractivePreviewCanvas(
                    sources = activeScene?.sources.orEmpty(),
                    selectedSourceId = state.selectedSourceId,
                    sceneName = sceneName,
                    resolutionLabel = state.recordingResolution.shortLabel,
                    outputWidth = state.outputWidth,
                    outputHeight = state.outputHeight,
                    recording = state.recording,
                    recordingMs = state.recordingMs,
                    previewEnabled = state.previewEnabled,
                    canvasLocked = canvasLocked,
                    isFullscreen = false,
                    onToggleLock = { canvasLocked = !canvasLocked },
                    onTogglePreview = { engine.setPreviewEnabled(!state.previewEnabled) },
                    onToggleFullscreen = { isPreviewFullscreen = true },
                    onSelectSource = { engine.selectSource(it) },
                    onUpdateTransform = { id, t -> engine.setTransform(id, t) },
                    onAttachPreview = { surface, w, h -> engine.attachPreview(surface, w, h) },
                    onDetachPreview = { engine.detachPreview() },
                )

                SceneStrip(
                    scenes = state.project.scenes,
                    activeSceneId = state.project.activeSceneId,
                    onSelectScene = { engine.setActiveScene(it) },
                    onAddScene = { engine.addScene(it) },
                    onRenameScene = { id, name -> engine.renameScene(id, name) },
                    onDuplicateScene = { id, newName -> engine.duplicateScene(id, newName) },
                    onRemoveScene = { engine.removeScene(it) },
                )

                PrimaryTransportBar(
                    state = state,
                    onToggleRecording = onToggleRecording,
                    onAddMarker = { engine.addMarker() },
                )

                StudioDockTabBar(activeTab = activeTab, onSelectTab = { activeTab = it })

                StudioDockContent(
                    tab = activeTab,
                    state = state,
                    engine = engine,
                    profileStore = profileStore,
                    chatController = chatController,
                    appVersion = appVersion,
                    onRequestScreenCapture = onRequestScreenCapture,
                    onPickImageOverlay = onPickImageOverlay,
                    onPickVideoOverlay = onPickVideoOverlay,
                    onOpenCameraNodeScanner = onOpenCameraNodeScanner,
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    }

    // Dialog Pratinjau & Simpan Setelah Rekaman Berhenti
    val reviewFile = state.reviewFile
    if (reviewFile != null && reviewFile.exists()) {
        ReviewRecordingDialog(
            file = reviewFile,
            onExport = { onExportVideo(reviewFile) },
            onKeep = { engine.clearReviewFile() },
            onDelete = {
                reviewFile.delete()
                engine.clearReviewFile()
            },
        )
    }
}

@Composable
private fun ErrorBanner(
    errorRes: Int?,
    errorDetail: String? = null,
    onDismiss: () -> Unit,
) {
    if (errorRes == null) return
    val baseMsg = stringResource(errorRes)
    val fullMsg = if (!errorDetail.isNullOrBlank()) "$baseMsg ($errorDetail)" else baseMsg
    Surface(
        color = Rekam.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Rekam.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = fullMsg,
                fontSize = 12.sp,
                color = Rekam,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close), fontSize = 12.sp, color = Rekam)
            }
        }
    }
}

/**
 * Bilah Rekam Utama: tidak bergantung warna saja — memakai bentuk (lingkaran -> kotak) + label + durasi.
 */
@Composable
private fun PrimaryTransportBar(
    state: EngineState,
    onToggleRecording: () -> Unit,
    onAddMarker: () -> Unit,
) {
    val buttonColor by animateColorAsState(
        targetValue = if (state.recording) Rekam else Aksen,
        label = "recordButtonColor",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onToggleRecording,
            enabled = !state.savingRecording,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // Bentuk berbeda: lingkaran saat siap rekam, kotak membulat saat merekam (PRD 8.1)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = Color.White,
                            shape = if (state.recording) RoundedCornerShape(3.dp) else CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(10.dp))
                val label = when {
                    state.savingRecording -> stringResource(R.string.recording_saving)
                    state.recording -> {
                        val detik = state.recordingMs / 1000
                        val durasi = String.format(
                            Locale.US,
                            "%02d:%02d",
                            detik / 60,
                            detik % 60,
                        )
                        "${stringResource(R.string.recording_stop)} • $durasi"
                    }
                    else -> stringResource(R.string.recording_start)
                }
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (state.recording) {
            OutlinedButton(
                onClick = onAddMarker,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Garis),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkAdd,
                    contentDescription = null,
                    tint = Aksen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.recording_marker, state.markers),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Aksen,
                )
            }
        }
    }
}

@Composable
private fun StudioDockTabBar(
    activeTab: StudioTab,
    onSelectTab: (StudioTab) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Kartu,
        border = BorderStroke(1.dp, Garis),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StudioTab.entries.forEach { tab ->
                val selected = tab == activeTab
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) Color(0xFFE7EFF8) else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable { onSelectTab(tab) },
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = stringResource(tab.labelRes),
                            tint = if (selected) Aksen else TeksSekunder,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(tab.labelRes),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Aksen else TeksSekunder,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioDockContent(
    tab: StudioTab,
    state: EngineState,
    engine: AppEngine,
    profileStore: StreamProfileStore,
    chatController: ChatController,
    appVersion: String,
    onRequestScreenCapture: () -> Unit,
    onPickImageOverlay: () -> Unit,
    onPickVideoOverlay: () -> Unit,
    onOpenCameraNodeScanner: () -> Unit,
) {
    when (tab) {
        StudioTab.SOURCES -> SourcesPanel(
            state = state,
            engine = engine,
            onRequestScreenCapture = onRequestScreenCapture,
            onPickImageOverlay = onPickImageOverlay,
            onPickVideoOverlay = onPickVideoOverlay,
        )

        StudioTab.MIXER -> AudioMixerPanel(
            state = state,
            engine = engine,
        )

        StudioTab.STREAM -> StreamChatPanel(
            state = state,
            engine = engine,
            profileStore = profileStore,
            chatController = chatController,
        )

        StudioTab.SETTINGS -> SettingsPanel(
            state = state,
            engine = engine,
            appVersion = appVersion,
            onOpenCameraNodeScanner = onOpenCameraNodeScanner,
        )
    }
}

@Composable
private fun ReviewRecordingDialog(
    file: File,
    onExport: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = {
            Text(
                text = stringResource(R.string.review_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Teks,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    elevation = CardDefaults.cardElevation(2.dp),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val mediaController = MediaController(ctx)
                                mediaController.setAnchorView(this)
                                setMediaController(mediaController)
                                setVideoPath(file.absolutePath)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Text(
                    text = stringResource(R.string.recording_file_label, file.name),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Teks,
                )
                Text(
                    text = stringResource(
                        R.string.recording_size_label,
                        file.length() / (1024 * 1024),
                        file.length() / 1024,
                    ),
                    fontSize = 12.sp,
                    color = TeksSekunder,
                )

                OutlinedButton(
                    onClick = onExport,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Garis),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(stringResource(R.string.review_export), color = Aksen)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(R.string.review_keep), fontWeight = FontWeight.Bold, color = Berhasil)
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.review_delete), color = Rekam)
            }
        },
        containerColor = Kartu,
        shape = RoundedCornerShape(16.dp),
    )
}
