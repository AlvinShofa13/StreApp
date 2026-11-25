package com.studiopocket.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
import com.studiopocket.core.audio.AudioRecorder
import com.studiopocket.core.video.GlCompositor
import com.studiopocket.core.video.LayerRect
import com.studiopocket.core.video.VideoEncoder
import com.studiopocket.model.Source
import com.studiopocket.model.SourceStatus
import com.studiopocket.source.camera.CameraSource
import com.studiopocket.source.remote.PairingInfo
import com.studiopocket.source.remote.RemoteControl
import com.studiopocket.source.remote.RemoteNodeClient
import com.studiopocket.source.remote.RemoteNodeStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mode HP B (Camera Node — Yang Memindai QR Host):
 * Menampilkan pratinjau ringan, pindai QR pairing (atau input manual), mengirim H.264 720p 30fps
 * dan audio mic opsional ke Host, serta menerima kontrol jarak jauh.
 */
class CameraNodeActivity : ComponentActivity() {

    private var compositor: GlCompositor? = null
    private var cameraSource: CameraSource? = null
    private var encoder: VideoEncoder? = null
    private var audioRecorder: AudioRecorder? = null
    private var client: RemoteNodeClient? = null

    private var useFrontCamera by mutableStateOf(false)
    private var sendAudio by mutableStateOf(false)
    private var torchEnabled by mutableStateOf(false)
    private var zoomRatio by mutableFloatStateOf(1f)
    private var maxZoomRatio by mutableFloatStateOf(4f)
    private var supportsTorch by mutableStateOf(false)

    private var nodeStatus by mutableStateOf(RemoteNodeStatus.TERPUTUS)
    private var connectedTargetLabel by mutableStateOf("")
    private var manualHost by mutableStateOf("")
    private var manualPort by mutableStateOf("45678")
    private var manualToken by mutableStateOf("")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true) {
            startCameraPipeline()
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        val parsed = PairingInfo.parse(raw)
        if (parsed == null) {
            Toast.makeText(this, getString(R.string.remote_invalid_qr), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        manualHost = parsed.host
        manualPort = parsed.port.toString()
        manualToken = parsed.token
        connectToHost(parsed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPipeline()
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }

        setContent {
            StreAppTheme {
                CameraNodeScreen(
                    status = nodeStatus,
                    connectedTarget = connectedTargetLabel,
                    useFrontCamera = useFrontCamera,
                    sendAudio = sendAudio,
                    torchEnabled = torchEnabled,
                    supportsTorch = supportsTorch,
                    zoomRatio = zoomRatio,
                    maxZoomRatio = maxZoomRatio,
                    manualHost = manualHost,
                    manualPort = manualPort,
                    manualToken = manualToken,
                    onManualHostChange = { manualHost = it },
                    onManualPortChange = { manualPort = it },
                    onManualTokenChange = { manualToken = it },
                    onBack = { finish() },
                    onScanQr = { launchQrScanner() },
                    onManualConnect = {
                        val p = manualPort.toIntOrNull() ?: 45678
                        val h = manualHost.trim()
                        val t = manualToken.trim()
                        if (h.isNotEmpty() && t.isNotEmpty()) {
                            connectToHost(PairingInfo(h, p, t, "manual"))
                        }
                    },
                    onDisconnect = { disconnectFromHost() },
                    onSwitchCamera = {
                        useFrontCamera = !useFrontCamera
                        restartCameraSource()
                    },
                    onToggleSendAudio = { enabled ->
                        sendAudio = enabled
                        syncNodeAudio()
                    },
                    onZoomChange = { z ->
                        zoomRatio = z
                        cameraSource?.setZoom(z)
                    },
                    onFocusAuto = { cameraSource?.setFocusAuto() },
                    onFocusLock = { cameraSource?.setFocusLocked() },
                    onToggleTorch = {
                        torchEnabled = !torchEnabled
                        cameraSource?.setTorch(torchEnabled)
                    },
                    onBindSurface = { surface, w, h ->
                        compositor?.setOutputSurface(surface, w, h)
                    },
                    onUnbindSurface = {
                        compositor?.setOutputSurface(null, 0, 0)
                    },
                )
            }
        }
    }

    private fun launchQrScanner() {
        // Lepas kamera sementara saat pemindai QR membuka kamera sistem
        cameraSource?.stop()
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.remote_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(opts)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (compositor == null) startCameraPipeline() else cameraSource?.start()
        }
    }

    private fun startCameraPipeline() {
        if (compositor != null) return
        val c = GlCompositor(canvasWidth = 1280, canvasHeight = 720)
        c.encoderFps = 30
        c.previewFps = 24
        c.start()
        compositor = c
        attachCameraToCompositor(c)
    }

    private fun restartCameraSource() {
        val c = compositor ?: return
        cameraSource?.stop()
        c.removeLayer(LAYER_ID)
        attachCameraToCompositor(c)
    }

    private fun attachCameraToCompositor(c: GlCompositor) {
        val cam = CameraSource(
            context = this,
            name = Build.MODEL ?: "Camera Node",
            useFrontFacing = useFrontCamera,
        )
        cam.statusListener = object : Source.StatusListener {
            override fun onStatusChanged(source: Source, status: SourceStatus) {
                if (status == SourceStatus.AKTIF) {
                    runOnUiThread {
                        maxZoomRatio = cam.maxZoom.coerceIn(1f, 6f)
                        supportsTorch = cam.supportsTorch
                    }
                    c.updateLayerTransform(
                        id = LAYER_ID,
                        rotationDegrees = cam.getGlRotation(),
                        isMirrored = cam.isFrontFacing,
                        sourceWidth = cam.previewWidth,
                        sourceHeight = cam.previewHeight,
                    )
                }
            }
        }
        cameraSource = cam
        c.addOesLayer(
            id = LAYER_ID,
            rect = LayerRect(0f, 0f, 1280f, 720f),
            zIndex = 0,
            rotationDegrees = cam.getGlRotation(),
            isMirrored = cam.isFrontFacing,
            sourceWidth = cam.previewWidth,
            sourceHeight = cam.previewHeight,
        ) { texture ->
            cam.setPreview(texture)
        }
        cam.start()
    }

    private fun connectToHost(pairing: PairingInfo) {
        disconnectFromHost()
        val c = compositor ?: run {
            startCameraPipeline()
            compositor ?: return
        }
        cameraSource?.start()

        val nodeName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Camera Node"
        val nodeClient = RemoteNodeClient(pairing, nodeName)
        nodeClient.onStatus = { status ->
            runOnUiThread { nodeStatus = status }
        }
        nodeClient.onControl = { ctrl ->
            runOnUiThread { applyRemoteControl(ctrl) }
        }
        client = nodeClient
        connectedTargetLabel = "${pairing.host}:${pairing.port}"
        nodeClient.start()

        val ve = VideoEncoder(
            width = 1280,
            height = 720,
            frameRate = 30,
            bitRate = 2_500_000,
        )
        ve.onFormatAvailable = { format: MediaFormat ->
            val sps = extractBytes(format.getByteBuffer("csd-0"))
            val pps = extractBytes(format.getByteBuffer("csd-1"))
            if (sps != null && pps != null) {
                nodeClient.sendVideoConfig(1280, 720, sps, pps)
            }
        }
        ve.onSampleAvailable = { buf: ByteBuffer, info: MediaCodec.BufferInfo ->
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isConfig && info.size > 0) {
                val data = ByteArray(info.size)
                val dup = buf.duplicate()
                dup.position(info.offset)
                dup.limit(info.offset + info.size)
                dup.get(data)
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                nodeClient.sendVideoSample(data, info.presentationTimeUs, isKey)
            }
        }
        ve.start()
        encoder = ve
        c.setEncoderSurface(ve.inputSurface, SystemClock.elapsedRealtimeNanos())
        syncNodeAudio()
    }

    private fun syncNodeAudio() {
        audioRecorder?.stop()
        audioRecorder = null
        val nodeClient = client ?: return
        if (!sendAudio) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val rec = AudioRecorder(sampleRate = 48000)
        rec.onPcmDataAvailable = { bytes, read, _ ->
            val shortsCount = read / 2
            if (shortsCount > 0) {
                val mono = ShortArray(shortsCount)
                ByteBuffer.wrap(bytes, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(mono)
                val stereo = ShortArray(shortsCount * 2)
                for (i in 0 until shortsCount) {
                    stereo[i * 2] = mono[i]
                    stereo[i * 2 + 1] = mono[i]
                }
                nodeClient.sendAudio(stereo, shortsCount)
            }
        }
        rec.start()
        audioRecorder = rec
    }

    private fun applyRemoteControl(ctrl: RemoteControl) {
        val cam = cameraSource ?: return
        ctrl.zoom?.let {
            zoomRatio = it.coerceIn(1f, maxZoomRatio)
            cam.setZoom(zoomRatio)
        }
        ctrl.exposure?.let { cam.setExposureCompensation(it) }
        ctrl.torch?.let {
            torchEnabled = it
            cam.setTorch(it)
        }
        when (ctrl.focusMode) {
            "auto" -> cam.setFocusAuto()
            "lock" -> cam.setFocusLocked()
            "manual" -> ctrl.focusDistance?.let { cam.setFocusDistance(it) }
        }
        ctrl.audioEnabled?.let {
            sendAudio = it
            syncNodeAudio()
        }
    }

    private fun extractBytes(buffer: ByteBuffer?): ByteArray? {
        val b = buffer?.duplicate() ?: return null
        val arr = ByteArray(b.remaining())
        b.get(arr)
        return arr
    }

    private fun disconnectFromHost() {
        audioRecorder?.stop()
        audioRecorder = null
        compositor?.setEncoderSurface(null)
        encoder?.stop()
        encoder = null
        client?.stop()
        client = null
        nodeStatus = RemoteNodeStatus.TERPUTUS
        connectedTargetLabel = ""
    }

    override fun onDestroy() {
        disconnectFromHost()
        cameraSource?.stop()
        cameraSource = null
        compositor?.stop()
        compositor = null
        super.onDestroy()
    }

    companion object {
        private const val LAYER_ID = "node_camera"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CameraNodeScreen(
    status: RemoteNodeStatus,
    connectedTarget: String,
    useFrontCamera: Boolean,
    sendAudio: Boolean,
    torchEnabled: Boolean,
    supportsTorch: Boolean,
    zoomRatio: Float,
    maxZoomRatio: Float,
    manualHost: String,
    manualPort: String,
    manualToken: String,
    onManualHostChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onManualTokenChange: (String) -> Unit,
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onManualConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSendAudio: (Boolean) -> Unit,
    onZoomChange: (Float) -> Unit,
    onFocusAuto: () -> Unit,
    onFocusLock: () -> Unit,
    onToggleTorch: () -> Unit,
    onBindSurface: (Surface, Int, Int) -> Unit,
    onUnbindSurface: () -> Unit,
) {
    val isConnected = status == RemoteNodeStatus.AKTIF || status == RemoteNodeStatus.MENYAMBUNG
    val statusColor = when (status) {
        RemoteNodeStatus.AKTIF -> Berhasil
        RemoteNodeStatus.MENYAMBUNG -> Peringatan
        RemoteNodeStatus.GALAT -> Rekam
        RemoteNodeStatus.TERPUTUS -> TeksSekunder
    }
    val statusText = when (status) {
        RemoteNodeStatus.AKTIF -> stringResource(R.string.remote_status_active)
        RemoteNodeStatus.MENYAMBUNG -> stringResource(R.string.remote_status_connecting)
        else -> stringResource(R.string.remote_status_disconnected)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.remote_node_role),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teks,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Teks,
                        )
                    }
                },
                actions = {
                    ComposeSurface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(end = 10.dp),
                    ) {
                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Latar),
            )
        },
        containerColor = Latar,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Pratinjau Kamera Ringan (720p 30fps)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18212B)),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                                        onBindSurface(Surface(t), w, h)
                                    }

                                    override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {
                                        onBindSurface(Surface(t), w, h)
                                    }

                                    override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                                        onUnbindSurface()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Tombol Pindai QR dari Host
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Kartu),
                border = BorderStroke(1.dp, Garis),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (connectedTarget.isNotBlank()) {
                        Text(
                            text = "$statusText • $connectedTarget",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onScanQr,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Aksen),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.remote_scan_qr_btn), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        if (isConnected) {
                            OutlinedButton(
                                onClick = onDisconnect,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Rekam),
                                modifier = Modifier.height(48.dp),
                            ) {
                                Text(stringResource(R.string.remote_disconnect), fontSize = 12.sp, color = Rekam)
                            }
                        }
                    }

                    // Input Manual IP & Token (cadangan jika QR sulit dipindai)
                    Text(
                        text = stringResource(R.string.remote_manual_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TeksSekunder,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = onManualHostChange,
                            label = { Text(stringResource(R.string.remote_host_ip), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = onManualPortChange,
                            label = { Text(stringResource(R.string.remote_port), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = onManualTokenChange,
                            label = { Text(stringResource(R.string.remote_token), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onManualConnect,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Aksen),
                            modifier = Modifier.height(52.dp),
                        ) {
                            Text(stringResource(R.string.remote_connect), fontSize = 12.sp, color = Aksen)
                        }
                    }
                }
            }

            // Kontrol Kamera Lokal HP B
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Kartu),
                border = BorderStroke(1.dp, Garis),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.source_camera_controls),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teks,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = onSwitchCamera,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                        ) {
                            Icon(Icons.Outlined.Cameraswitch, contentDescription = null, modifier = Modifier.size(16.dp), tint = Aksen)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (useFrontCamera) R.string.source_type_camera_front else R.string.source_type_camera_back,
                                ),
                                fontSize = 12.sp,
                                color = Teks,
                            )
                        }
                        OutlinedButton(
                            onClick = onFocusAuto,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                        ) {
                            Text(stringResource(R.string.camera_focus_auto), fontSize = 12.sp, color = Teks)
                        }
                        OutlinedButton(
                            onClick = onFocusLock,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                        ) {
                            Text(stringResource(R.string.camera_focus_lock), fontSize = 12.sp, color = Teks)
                        }
                        if (supportsTorch) {
                            FilterChip(
                                selected = torchEnabled,
                                onClick = onToggleTorch,
                                label = { Text(stringResource(R.string.camera_torch), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFE7EFF8),
                                    selectedLabelColor = Aksen,
                                ),
                            )
                        }
                    }

                    if (maxZoomRatio > 1.05f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${stringResource(R.string.camera_zoom)} ${String.format("%.1fx", zoomRatio)}",
                                fontSize = 12.sp,
                                color = Teks,
                                modifier = Modifier.width(86.dp),
                            )
                            Slider(
                                value = zoomRatio,
                                onValueChange = onZoomChange,
                                valueRange = 1f..maxZoomRatio,
                                colors = SliderDefaults.colors(thumbColor = Aksen, activeTrackColor = Aksen),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.remote_send_audio),
                            fontSize = 13.sp,
                            color = Teks,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = sendAudio, onCheckedChange = onToggleSendAudio)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
