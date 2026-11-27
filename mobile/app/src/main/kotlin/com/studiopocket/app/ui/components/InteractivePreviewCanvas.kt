package com.studiopocket.app.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.studiopocket.app.R
import com.studiopocket.app.ui.theme.Aksen
import com.studiopocket.app.ui.theme.Rekam
import com.studiopocket.model.SceneSource
import com.studiopocket.model.Transform
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
private enum class TimerDisplayMode { COMPACT, MINIMIZED, HIDDEN }

private const val SNAP_THRESHOLD = 0.025f
private const val MIN_SIZE_FRACTION = 0.10f

/**
 * Kanvas Pratinjau OBS Interaktif:
 * - Menampilkan output kompositor OpenGL secara real time (mode normal dan layar penuh).
 * - Timer rekaman ringkas yang bisa dikecilkan (minimized dot) atau disembunyikan penuh.
 * - Ketuk sumber untuk memilih, geser untuk memindah (dengan snap tepi & tengah),
 *   dan tarik salah satu dari 4 handle sudut untuk mengatur besar-kecil ukuran sumber.
 */
@Composable
fun InteractivePreviewCanvas(
    sources: List<SceneSource>,
    selectedSourceId: String?,
    sceneName: String,
    resolutionLabel: String,
    outputWidth: Int,
    outputHeight: Int,
    recording: Boolean,
    recordingMs: Long,
    previewEnabled: Boolean,
    canvasLocked: Boolean,
    isFullscreen: Boolean,
    onToggleLock: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleRecording: (() -> Unit)? = null,
    onSelectSource: (String?) -> Unit,
    onUpdateTransform: (String, Transform) -> Unit,
    onAttachPreview: (Surface, Int, Int) -> Unit,
    onDetachPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestSources by rememberUpdatedState(sources)
    val latestSelectedId by rememberUpdatedState(selectedSourceId)
    val latestLocked by rememberUpdatedState(canvasLocked)
    val latestOnSelect by rememberUpdatedState(onSelectSource)
    val latestOnTransform by rememberUpdatedState(onUpdateTransform)

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var snapCenterX by remember { mutableStateOf(false) }
    var snapCenterY by remember { mutableStateOf(false) }
    var activeDragMode by remember { mutableStateOf(DragMode.NONE) }
    var dragSourceId by remember { mutableStateOf<String?>(null) }
    var dragTransform by remember { mutableStateOf<Transform?>(null) }

    var timerMode by rememberSaveable { mutableStateOf(TimerDisplayMode.COMPACT) }
    var fullscreenControlsVisible by rememberSaveable { mutableStateOf(true) }

    val aspectRatio = (outputWidth.toFloat() / outputHeight.coerceAtLeast(1).toFloat())
        .coerceIn(0.5f, 2.2f)
    val isPortraitCanvas = outputHeight > outputWidth

    val containerModifier = if (isFullscreen) {
        modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
    } else {
        modifier
            .fillMaxWidth()
            .then(if (isPortraitCanvas) Modifier.heightIn(max = 320.dp) else Modifier)
    }

    Card(
        modifier = containerModifier,
        shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFullscreen) Color(0xFF0B0F14) else Color(0xFF18212B),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFullscreen) 0.dp else 2.dp),
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isFullscreen) {
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    fullscreenControlsVisible = !fullscreenControlsVisible
                                }
                            }
                    } else if (isPortraitCanvas) {
                        Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .then(
                        when {
                            isFullscreen -> Modifier.aspectRatio(aspectRatio)
                            isPortraitCanvas -> Modifier
                                .fillMaxHeight()
                                .aspectRatio(aspectRatio)
                            else -> Modifier.fillMaxSize()
                        },
                    )
                    .onSizeChanged { boxSize = it },
            ) {
                if (previewEnabled) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        onAttachPreview(Surface(texture), width, height)
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        onAttachPreview(Surface(texture), width, height)
                                    }

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        onDetachPreview()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1F2933))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.canvas_preview_disabled_msg),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onTogglePreview) {
                            Text(
                                text = stringResource(R.string.canvas_preview_on),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                // Interactive Gesture & Bounding Box Overlay (hanya di mode non-fullscreen)
                if (previewEnabled && !canvasLocked && !isFullscreen) {
                    val selectedSource = sources.firstOrNull { it.id == selectedSourceId && it.visible }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(boxSize) {
                                detectTapGestures { offset ->
                                    if (latestLocked || boxSize.width <= 0 || boxSize.height <= 0) return@detectTapGestures
                                    val nx = offset.x / boxSize.width.toFloat()
                                    val ny = offset.y / boxSize.height.toFloat()
                                    val hit = latestSources.asReversed().firstOrNull { src ->
                                        src.visible &&
                                            nx in src.transform.x..(src.transform.x + src.transform.width) &&
                                            ny in src.transform.y..(src.transform.y + src.transform.height)
                                    }
                                    latestOnSelect(hit?.id)
                                }
                            }
                            .pointerInput(boxSize) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        if (latestLocked || boxSize.width <= 0 || boxSize.height <= 0) {
                                            activeDragMode = DragMode.NONE
                                            return@detectDragGestures
                                        }
                                        val w = boxSize.width.toFloat()
                                        val h = boxSize.height.toFloat()
                                        val nx = startOffset.x / w
                                        val ny = startOffset.y / h

                                        val current = latestSources.firstOrNull {
                                            it.id == latestSelectedId && it.visible
                                        } ?: latestSources.asReversed().firstOrNull { src ->
                                            src.visible &&
                                                nx in src.transform.x..(src.transform.x + src.transform.width) &&
                                                ny in src.transform.y..(src.transform.y + src.transform.height)
                                        }

                                        if (current == null) {
                                            activeDragMode = DragMode.NONE
                                            dragSourceId = null
                                            return@detectDragGestures
                                        }
                                        latestOnSelect(current.id)
                                        dragSourceId = current.id
                                        dragTransform = current.transform

                                        val t = current.transform
                                        val handleRadiusPx = 38.dp.toPx()
                                        val tl = Offset(t.x * w, t.y * h)
                                        val tr = Offset((t.x + t.width) * w, t.y * h)
                                        val bl = Offset(t.x * w, (t.y + t.height) * h)
                                        val br = Offset((t.x + t.width) * w, (t.y + t.height) * h)

                                        activeDragMode = when {
                                            hypot(startOffset.x - br.x, startOffset.y - br.y) <= handleRadiusPx -> DragMode.BOTTOM_RIGHT
                                            hypot(startOffset.x - tl.x, startOffset.y - tl.y) <= handleRadiusPx -> DragMode.TOP_LEFT
                                            hypot(startOffset.x - tr.x, startOffset.y - tr.y) <= handleRadiusPx -> DragMode.TOP_RIGHT
                                            hypot(startOffset.x - bl.x, startOffset.y - bl.y) <= handleRadiusPx -> DragMode.BOTTOM_LEFT
                                            else -> DragMode.MOVE
                                        }
                                    },
                                    onDragEnd = {
                                        activeDragMode = DragMode.NONE
                                        dragSourceId = null
                                        dragTransform = null
                                        snapCenterX = false
                                        snapCenterY = false
                                    },
                                    onDragCancel = {
                                        activeDragMode = DragMode.NONE
                                        dragSourceId = null
                                        dragTransform = null
                                        snapCenterX = false
                                        snapCenterY = false
                                    },
                                    onDrag = { change, dragAmount ->
                                        val id = dragSourceId ?: return@detectDragGestures
                                        val currentT = dragTransform ?: return@detectDragGestures
                                        if (boxSize.width <= 0 || boxSize.height <= 0) return@detectDragGestures
                                        change.consume()

                                        val dx = dragAmount.x / boxSize.width.toFloat()
                                        val dy = dragAmount.y / boxSize.height.toFloat()

                                        val updated = when (activeDragMode) {
                                            DragMode.MOVE -> {
                                                var nx = (currentT.x + dx).coerceIn(0f, 1f - currentT.width)
                                                var ny = (currentT.y + dy).coerceIn(0f, 1f - currentT.height)
                                                val centerX = (1f - currentT.width) / 2f
                                                val centerY = (1f - currentT.height) / 2f
                                                snapCenterX = abs(nx - centerX) < SNAP_THRESHOLD
                                                snapCenterY = abs(ny - centerY) < SNAP_THRESHOLD
                                                if (snapCenterX) nx = centerX
                                                else if (nx < SNAP_THRESHOLD) nx = 0f
                                                else if (abs(nx - (1f - currentT.width)) < SNAP_THRESHOLD) nx = 1f - currentT.width

                                                if (snapCenterY) ny = centerY
                                                else if (ny < SNAP_THRESHOLD) ny = 0f
                                                else if (abs(ny - (1f - currentT.height)) < SNAP_THRESHOLD) ny = 1f - currentT.height

                                                currentT.copy(x = nx, y = ny)
                                            }

                                            DragMode.BOTTOM_RIGHT -> {
                                                val nw = (currentT.width + dx).coerceIn(MIN_SIZE_FRACTION, 1f - currentT.x)
                                                val nh = (currentT.height + dy).coerceIn(MIN_SIZE_FRACTION, 1f - currentT.y)
                                                currentT.copy(width = nw, height = nh)
                                            }

                                            DragMode.TOP_LEFT -> {
                                                val right = currentT.x + currentT.width
                                                val bottom = currentT.y + currentT.height
                                                val nx = (currentT.x + dx).coerceIn(0f, right - MIN_SIZE_FRACTION)
                                                val ny = (currentT.y + dy).coerceIn(0f, bottom - MIN_SIZE_FRACTION)
                                                currentT.copy(x = nx, y = ny, width = right - nx, height = bottom - ny)
                                            }

                                            DragMode.TOP_RIGHT -> {
                                                val bottom = currentT.y + currentT.height
                                                val nw = (currentT.width + dx).coerceIn(MIN_SIZE_FRACTION, 1f - currentT.x)
                                                val ny = (currentT.y + dy).coerceIn(0f, bottom - MIN_SIZE_FRACTION)
                                                currentT.copy(y = ny, width = nw, height = bottom - ny)
                                            }

                                            DragMode.BOTTOM_LEFT -> {
                                                val right = currentT.x + currentT.width
                                                val nx = (currentT.x + dx).coerceIn(0f, right - MIN_SIZE_FRACTION)
                                                val nh = (currentT.height + dy).coerceIn(MIN_SIZE_FRACTION, 1f - currentT.y)
                                                currentT.copy(x = nx, width = right - nx, height = nh)
                                            }

                                            DragMode.NONE -> currentT
                                        }
                                        dragTransform = updated
                                        latestOnTransform(id, updated)
                                    },
                                )
                            },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            if (snapCenterX) {
                                drawLine(
                                    color = Aksen,
                                    start = Offset(w / 2f, 0f),
                                    end = Offset(w / 2f, h),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }
                            if (snapCenterY) {
                                drawLine(
                                    color = Aksen,
                                    start = Offset(0f, h / 2f),
                                    end = Offset(w, h / 2f),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }

                            val active = selectedSource
                            if (active != null) {
                                val t = dragTransform?.takeIf { dragSourceId == active.id } ?: active.transform
                                val left = t.x * w
                                val top = t.y * h
                                val rectW = t.width * w
                                val rectH = t.height * h

                                drawRect(
                                    color = Aksen,
                                    topLeft = Offset(left, top),
                                    size = Size(rectW, rectH),
                                    style = Stroke(width = 2.dp.toPx()),
                                )

                                val r = 6.dp.toPx()
                                val corners = listOf(
                                    Offset(left, top),
                                    Offset(left + rectW, top),
                                    Offset(left, top + rectH),
                                    Offset(left + rectW, top + rectH),
                                )
                                for (pt in corners) {
                                    drawCircle(color = Color.White, radius = r, center = pt)
                                    drawCircle(
                                        color = Aksen,
                                        radius = r,
                                        center = pt,
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bilah Kontrol Atas (di mode fullscreen bisa disembunyikan dengan ketuk layar)
            val showTopBar = !isFullscreen || fullscreenControlsVisible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 8.dp, vertical = if (isFullscreen) 14.dp else 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Pojok kiri atas: Timer Rekaman Kecil (atau nama sumber terpilih yang dibatasi weight agar tidak mendorong tombol kanan)
                Box(modifier = Modifier.weight(1f)) {
                    if (recording) {
                        CompactRecordingTimer(
                            recordingMs = recordingMs,
                            mode = timerMode,
                            onModeChange = { timerMode = it },
                        )
                    } else if (!isFullscreen) {
                        val selected = sources.firstOrNull { it.id == selectedSourceId && it.visible }
                        if (selected != null && !canvasLocked) {
                            ComposeSurface(
                                color = Color.Black.copy(alpha = 0.62f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.canvas_selected_size,
                                        selected.name,
                                        (selected.transform.width * 100).roundToInt(),
                                        (selected.transform.height * 100).roundToInt(),
                                    ),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                // Pojok kanan atas: Kontrol cepat & tombol Layar Penuh
                if (showTopBar) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Tombol munculkan timer kembali jika sedang disembunyikan
                        if (recording && timerMode == TimerDisplayMode.HIDDEN) {
                            ComposeSurface(
                                color = Color.Black.copy(alpha = 0.56f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { timerMode = TimerDisplayMode.COMPACT },
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Timer,
                                        contentDescription = stringResource(R.string.timer_show),
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }

                        if (!isFullscreen) {
                            ComposeSurface(
                                color = Color.Black.copy(alpha = 0.56f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { onToggleLock() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = if (canvasLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                        contentDescription = stringResource(
                                            if (canvasLocked) R.string.canvas_lock else R.string.canvas_unlock,
                                        ),
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(
                                            if (canvasLocked) R.string.canvas_lock else R.string.canvas_unlock,
                                        ),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }

                            ComposeSurface(
                                color = Color.Black.copy(alpha = 0.56f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { onTogglePreview() },
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (previewEnabled) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                        contentDescription = stringResource(
                                            if (previewEnabled) R.string.canvas_preview_off else R.string.canvas_preview_on,
                                        ),
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        } else if (onToggleRecording != null) {
                            // Tombol Rekam / Hentikan ringkas di mode Layar Penuh
                            ComposeSurface(
                                color = if (recording) Rekam.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.62f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { onToggleRecording() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(
                                                color = if (recording) Color.White else Rekam,
                                                shape = if (recording) RoundedCornerShape(2.dp) else CircleShape,
                                            ),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            if (recording) R.string.notif_stop else R.string.recording_start,
                                        ),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        // Tombol Layar Penuh / Keluar Layar Penuh
                        ComposeSurface(
                            color = Color.Black.copy(alpha = 0.62f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable { onToggleFullscreen() },
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = if (isFullscreen) 10.dp else 8.dp,
                                    vertical = if (isFullscreen) 6.dp else 5.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    contentDescription = stringResource(
                                        if (isFullscreen) R.string.canvas_fullscreen_exit else R.string.canvas_fullscreen_enter,
                                    ),
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(
                                        if (isFullscreen) R.string.canvas_fullscreen_exit else R.string.canvas_fullscreen_short,
                                    ),
                                    color = Color.White,
                                    fontSize = if (isFullscreen) 11.sp else 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            // Bilah info bawah (hanya di mode normal agar layar penuh bersih)
            if (!isFullscreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sceneName,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "$resolutionLabel • ${outputWidth}x${outputHeight}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/**
 * Timer rekaman ringkas:
 * - COMPACT: pill kecil (titik merah + MM:SS) dengan tombol kecilkan (-) dan sembunyikan (x).
 * - MINIMIZED: titik merah mini tidak mengganggu; ketuk untuk membuka kembali.
 * - HIDDEN: hilang total (munculkan lagi lewat tombol ikon jam di pojok kanan atas).
 */
@Composable
private fun CompactRecordingTimer(
    recordingMs: Long,
    mode: TimerDisplayMode,
    onModeChange: (TimerDisplayMode) -> Unit,
) {
    when (mode) {
        TimerDisplayMode.HIDDEN -> {
            Spacer(modifier = Modifier.width(1.dp))
        }

        TimerDisplayMode.MINIMIZED -> {
            ComposeSurface(
                color = Color.Black.copy(alpha = 0.52f),
                shape = CircleShape,
                modifier = Modifier.clickable { onModeChange(TimerDisplayMode.COMPACT) },
            ) {
                Box(
                    modifier = Modifier.padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Rekam, shape = CircleShape),
                    )
                }
            }
        }

        TimerDisplayMode.COMPACT -> {
            val sec = recordingMs / 1000
            val hours = sec / 3600
            val duration = if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, (sec % 3600) / 60, sec % 60)
            } else {
                String.format(Locale.US, "%02d:%02d", (sec % 3600) / 60, sec % 60)
            }

            ComposeSurface(
                color = Color.Black.copy(alpha = 0.58f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 7.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Rekam, shape = CircleShape),
                    )
                    Text(
                        text = duration,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onModeChange(TimerDisplayMode.MINIMIZED) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Remove,
                            contentDescription = stringResource(R.string.timer_minimize),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onModeChange(TimerDisplayMode.HIDDEN) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.timer_hide),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
