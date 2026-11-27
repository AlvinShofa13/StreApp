package com.studiopocket.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
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
import com.studiopocket.debug.DebugOverlay
import com.studiopocket.debug.DebugStats
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus
import com.studiopocket.source.uvc.UvcSource

/**
 * Layar Pratinjau UVC Direct (Capture Card / Webcam USB).
 */
class UvcPreviewActivity : ComponentActivity() {

    private val stats = DebugStats()
    private lateinit var source: UvcSource
    private var debugOverlay: DebugOverlay? = null

    private var statusMessageState by mutableStateOf("Menunggu capture card...")
    private var sourceStatusState by mutableStateOf(SourceStatus.SIAGA)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            mulai()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        source = UvcSource(this, getString(R.string.uvc_source_name))
        source.log = { Diag.catat(it) }
        stats.registerSource(source.name)
        source.statusListener = object : Source.StatusListener {
            override fun onStatusChanged(source: Source, status: SourceStatus) {
                sourceStatusState = status
                tampilkanStatus()
            }
        }

        val rootFrameLayout = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                StreAppTheme {
                    UvcScreen(
                        statusMessage = statusMessageState,
                        sourceStatus = sourceStatusState,
                        onBack = { finish() },
                        onToggleDebugOverlay = {
                            if (debugOverlay == null) {
                                debugOverlay = DebugOverlay(rootFrameLayout, stats)
                            }
                            debugOverlay?.toggle()
                        },
                        onBindTexture = { texture ->
                            source.setPreview(texture)
                        },
                    )
                }
            }
        }

        rootFrameLayout.addView(composeView)
        setContentView(rootFrameLayout)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            mulai()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun mulai() {
        try {
            source.start()
        } catch (t: Throwable) {
            statusMessageState = getString(R.string.uvc_status_error)
        }
        tampilkanStatus()
    }

    private fun tampilkanStatus() {
        val s = source.activeSize
        statusMessageState = when {
            source.status == SourceStatus.AKTIF && s != null ->
                getString(R.string.uvc_status_active, s.width, s.height, s.fps)

            source.status == SourceStatus.AKTIF -> getString(R.string.uvc_status_waiting)
            source.status == SourceStatus.TERPUTUS -> getString(R.string.uvc_status_disconnected)
            source.status == SourceStatus.GALAT -> getString(R.string.uvc_status_error)
            else -> getString(R.string.uvc_status_waiting)
        }
    }

    override fun onDestroy() {
        if (::source.isInitialized) source.stop()
        debugOverlay?.stop()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UvcScreen(
    statusMessage: String,
    sourceStatus: SourceStatus,
    onBack: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onBindTexture: (SurfaceTexture?) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Capture Card USB (UVC)",
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
            // 1. 16:9 Direct UVC Preview Card
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
                                        onBindTexture(texture)
                                    }

                                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        onBindTexture(null)
                                        return false
                                    }

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Status Banner Card
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
                        targetValue = when (sourceStatus) {
                            SourceStatus.AKTIF -> Color(0xFF4F8A6B)
                            SourceStatus.TERPUTUS, SourceStatus.GALAT -> Color(0xFFC0504D)
                            else -> Color(0xFFC98A2B)
                        },
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

            OutlinedButton(
                onClick = onToggleDebugOverlay,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("📊 Overlay Debug / FPS", fontSize = 14.sp)
            }
        }
    }
}
