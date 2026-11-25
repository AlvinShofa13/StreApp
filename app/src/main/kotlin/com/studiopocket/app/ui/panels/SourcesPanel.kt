package com.studiopocket.app.ui.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhotoCameraFront
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiopocket.app.R
import com.studiopocket.app.engine.AppEngine
import com.studiopocket.app.engine.EngineState
import com.studiopocket.app.ui.theme.Aksen
import com.studiopocket.app.ui.theme.Berhasil
import com.studiopocket.app.ui.theme.Garis
import com.studiopocket.app.ui.theme.Kartu
import com.studiopocket.app.ui.theme.Peringatan
import com.studiopocket.app.ui.theme.Rekam
import com.studiopocket.app.ui.theme.Teks
import com.studiopocket.app.ui.theme.TeksSekunder
import com.studiopocket.model.LayerShape
import com.studiopocket.model.SceneSource
import com.studiopocket.model.SceneTemplate
import com.studiopocket.model.SourceStatus
import com.studiopocket.model.SourceType
import kotlin.math.roundToInt

/**
 * Panel Sumber & Transformasi Layer ala OBS Mobile:
 * - Mengatur besar-kecil ukuran sumber (lebar, tinggi, isi penuh, tengah, bentuk).
 * - Mengatur urutan layer (naik/turun), visibilitas, kontrol kamera, template cepat, dan tambah sumber.
 */
@Composable
fun SourcesPanel(
    state: EngineState,
    engine: AppEngine,
    onRequestScreenCapture: () -> Unit,
    onPickImageOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scene = state.project.activeScene
    val sources = scene?.sources.orEmpty()
    val selected = sources.firstOrNull { it.id == state.selectedSourceId }

    var showAddDialog by remember { mutableStateOf(false) }
    var showTextOverlayDialog by remember { mutableStateOf(false) }
    var overlayTextInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Kartu Atur Ukuran & Bentuk Sumber Terpilih
        if (selected != null) {
            SelectedSourceTransformCard(
                source = selected,
                engine = engine,
            )
        } else if (sources.isNotEmpty()) {
            Surface(
                color = Color(0xFFE7EFF8),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.canvas_hint_select),
                    fontSize = 12.sp,
                    color = Aksen,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        // 2. Daftar Sumber / Layer (urutan atas = layer paling depan seperti OBS)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Kartu),
            border = BorderStroke(1.dp, Garis),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sources_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teks,
                    )
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Aksen),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.source_add),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (sources.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sources_empty),
                        fontSize = 12.sp,
                        color = TeksSekunder,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    val orderedTopFirst = sources.asReversed()
                    orderedTopFirst.forEachIndexed { idx, src ->
                        val isSelected = src.id == state.selectedSourceId
                        val status = state.sourceStatus[src.id] ?: SourceStatus.SIAGA
                        SourceLayerRow(
                            source = src,
                            status = status,
                            selected = isSelected,
                            canMoveUp = idx > 0,
                            canMoveDown = idx < orderedTopFirst.lastIndex,
                            onSelect = { engine.selectSource(if (isSelected) null else src.id) },
                            onToggleVisible = { engine.setVisible(src.id, !src.visible) },
                            onMoveUp = { engine.moveSource(src.id, up = true) },
                            onMoveDown = { engine.moveSource(src.id, up = false) },
                            onRemove = {
                                if (isSelected) engine.selectSource(null)
                                engine.removeSource(src.id)
                            },
                        )
                        if (idx < orderedTopFirst.lastIndex) {
                            HorizontalDivider(color = Garis, thickness = 1.dp)
                        }
                    }
                }
            }
        }

        // 3. Template Cepat Tata Letak (PRD FR-8.4)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Kartu),
            border = BorderStroke(1.dp, Garis),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.scene_templates),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Teks,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val templates = listOf(
                    SceneTemplate.LAYAR_PENUH to R.string.template_full,
                    SceneTemplate.GAMEPLAY_KAMERA to R.string.template_gameplay_cam,
                    SceneTemplate.LAYAR_KAMERA_SUDUT to R.string.template_screen_cam,
                    SceneTemplate.KAMERA_PENUH to R.string.template_camera,
                    SceneTemplate.DUA_KAMERA to R.string.template_dual_cam,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(templates) { (tpl, labelRes) ->
                        OutlinedButton(
                            onClick = { engine.applyTemplate(tpl) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                fontSize = 12.sp,
                                color = Teks,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog Tambah Sumber
    if (showAddDialog) {
        val labelUvc = stringResource(R.string.source_type_uvc)
        val labelCamFront = stringResource(R.string.source_type_camera_front)
        val labelCamBack = stringResource(R.string.source_type_camera_back)
        val labelScreen = stringResource(R.string.source_type_screen)
        val labelRemote = stringResource(R.string.source_type_remote)
        val labelTest = stringResource(R.string.test_pattern_source_name)

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.source_add),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Teks,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AddSourceOptionRow(Icons.Outlined.Usb, labelUvc) {
                        val id = engine.addSource(SourceType.UVC, labelUvc)
                        engine.selectSource(id)
                        showAddDialog = false
                    }
                    AddSourceOptionRow(Icons.Outlined.ScreenShare, labelScreen) {
                        val id = engine.addSource(SourceType.SCREEN, labelScreen)
                        engine.selectSource(id)
                        showAddDialog = false
                        if (!engine.hasProjection()) onRequestScreenCapture()
                    }
                    AddSourceOptionRow(Icons.Outlined.PhotoCameraFront, labelCamFront) {
                        val id = engine.addSource(SourceType.CAMERA, labelCamFront, deviceKey = "front")
                        engine.selectSource(id)
                        showAddDialog = false
                    }
                    AddSourceOptionRow(Icons.Outlined.Videocam, labelCamBack) {
                        val id = engine.addSource(SourceType.CAMERA, labelCamBack, deviceKey = "back")
                        engine.selectSource(id)
                        showAddDialog = false
                    }
                    AddSourceOptionRow(Icons.Outlined.Devices, labelRemote) {
                        val id = engine.addSource(SourceType.REMOTE, labelRemote)
                        engine.selectSource(id)
                        showAddDialog = false
                    }
                    AddSourceOptionRow(Icons.Outlined.TextFields, stringResource(R.string.source_type_text)) {
                        showAddDialog = false
                        overlayTextInput = ""
                        showTextOverlayDialog = true
                    }
                    AddSourceOptionRow(Icons.Outlined.Image, stringResource(R.string.source_type_image)) {
                        showAddDialog = false
                        onPickImageOverlay()
                    }
                    HorizontalDivider(color = Garis, modifier = Modifier.padding(vertical = 4.dp))
                    AddSourceOptionRow(Icons.Outlined.Tune, stringResource(R.string.source_type_test)) {
                        val id = engine.addSource(SourceType.TEST, labelTest)
                        engine.selectSource(id)
                        showAddDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TeksSekunder)
                }
            },
            containerColor = Kartu,
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showTextOverlayDialog) {
        val textTitle = stringResource(R.string.source_type_text)
        AlertDialog(
            onDismissRequest = { showTextOverlayDialog = false },
            title = { Text(textTitle, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = overlayTextInput,
                    onValueChange = { overlayTextInput = it },
                    label = { Text(stringResource(R.string.source_text_prompt)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = overlayTextInput.trim()
                        if (text.isNotEmpty()) {
                            val id = engine.addSource(SourceType.TEXT, text, content = text)
                            if (id != null) {
                                engine.setTransform(
                                    id,
                                    com.studiopocket.model.Transform(0.15f, 0.80f, 0.70f, 0.14f),
                                )
                                engine.selectSource(id)
                            }
                        }
                        showTextOverlayDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_add), color = Aksen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextOverlayDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TeksSekunder)
                }
            },
            containerColor = Kartu,
            shape = RoundedCornerShape(16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedSourceTransformCard(
    source: SceneSource,
    engine: AppEngine,
) {
    var keepRatio by remember(source.id) { mutableStateOf(true) }
    val t = source.transform

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Kartu),
        border = BorderStroke(1.5.dp, Aksen.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = source.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.canvas_keep_aspect),
                        fontSize = 11.sp,
                        color = TeksSekunder,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = keepRatio,
                        onCheckedChange = { keepRatio = it },
                    )
                }
            }

            // Slider Lebar (mengatur besar-kecil ukuran sumber)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.canvas_width)} ${(t.width * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    color = Teks,
                    modifier = Modifier.width(92.dp),
                )
                Slider(
                    value = t.width,
                    onValueChange = { newW ->
                        val ratio = if (t.width > 0.01f) t.height / t.width else 1f
                        val newH = if (keepRatio) (newW * ratio).coerceIn(0.10f, 1f) else t.height
                        engine.setTransform(source.id, t.copy(width = newW, height = newH))
                    },
                    valueRange = 0.10f..1f,
                    colors = SliderDefaults.colors(thumbColor = Aksen, activeTrackColor = Aksen),
                    modifier = Modifier.weight(1f),
                )
            }

            // Slider Tinggi (bebas saat kunci rasio dimatikan)
            if (!keepRatio) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${stringResource(R.string.canvas_height)} ${(t.height * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        color = Teks,
                        modifier = Modifier.width(92.dp),
                    )
                    Slider(
                        value = t.height,
                        onValueChange = { newH ->
                            engine.setTransform(source.id, t.copy(height = newH))
                        },
                        valueRange = 0.10f..1f,
                        colors = SliderDefaults.colors(thumbColor = Aksen, activeTrackColor = Aksen),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Tombol cepat: Isi Penuh, Tengah, dan Bentuk Overlay (FR-5.4)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { engine.resetSourceFullScreen(source.id) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Garis),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AspectRatio,
                        contentDescription = null,
                        tint = Teks,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.canvas_fit_full), fontSize = 12.sp, color = Teks)
                }

                OutlinedButton(
                    onClick = { engine.centerSource(source.id) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Garis),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CenterFocusWeak,
                        contentDescription = null,
                        tint = Teks,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.canvas_center), fontSize = 12.sp, color = Teks)
                }
            }

            // Pemilih bentuk (Persegi, Membulat, Bulat)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val shapes = listOf(
                    LayerShape.KOTAK to R.string.source_shape_rect,
                    LayerShape.SUDUT_MEMBULAT to R.string.source_shape_rounded,
                    LayerShape.BULAT to R.string.source_shape_circle,
                )
                shapes.forEach { (shape, labelRes) ->
                    FilterChip(
                        selected = source.shape == shape,
                        onClick = { engine.setShape(source.id, shape) },
                        label = { Text(stringResource(labelRes), fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }

            // Kontrol Kamera Lokal (FR-5.3) jika sumber adalah kamera
            if (source.type == SourceType.CAMERA) {
                val cam = engine.cameraSource(source.id)
                if (cam != null) {
                    HorizontalDivider(color = Garis, modifier = Modifier.padding(vertical = 4.dp))
                    var zoomVal by remember(source.id) { mutableFloatStateOf(1f) }
                    var expVal by remember(source.id) { mutableFloatStateOf(0f) }
                    var torchOn by remember(source.id) { mutableStateOf(false) }

                    Text(
                        text = stringResource(R.string.source_camera_controls),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TeksSekunder,
                    )
                    if (cam.maxZoom > 1.05f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${stringResource(R.string.camera_zoom)} ${String.format("%.1fx", zoomVal)}",
                                fontSize = 12.sp,
                                color = Teks,
                                modifier = Modifier.width(92.dp),
                            )
                            Slider(
                                value = zoomVal,
                                onValueChange = {
                                    zoomVal = it
                                    cam.setZoom(it)
                                },
                                valueRange = 1f..cam.maxZoom.coerceAtMost(6f),
                                colors = SliderDefaults.colors(thumbColor = Aksen, activeTrackColor = Aksen),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    val expRange = cam.exposeCompensationRange
                    if (expRange.last > expRange.first) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${stringResource(R.string.camera_exposure)} ${expVal.roundToInt()}",
                                fontSize = 12.sp,
                                color = Teks,
                                modifier = Modifier.width(92.dp),
                            )
                            Slider(
                                value = expVal,
                                onValueChange = {
                                    expVal = it
                                    cam.setExposureCompensation(it.roundToInt())
                                },
                                valueRange = expRange.first.toFloat()..expRange.last.toFloat(),
                                colors = SliderDefaults.colors(thumbColor = Aksen, activeTrackColor = Aksen),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { cam.setFocusAuto() },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Garis),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.camera_focus_auto), fontSize = 11.sp, color = Teks)
                        }
                        OutlinedButton(
                            onClick = { cam.setFocusLocked() },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Garis),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.camera_focus_lock), fontSize = 11.sp, color = Teks)
                        }
                        if (cam.supportsTorch) {
                            FilterChip(
                                selected = torchOn,
                                onClick = {
                                    torchOn = !torchOn
                                    cam.setTorch(torchOn)
                                },
                                label = { Text(stringResource(R.string.camera_torch), fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLayerRow(
    source: SceneSource,
    status: SourceStatus,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val icon = when (source.type) {
        SourceType.UVC -> Icons.Outlined.Usb
        SourceType.CAMERA -> Icons.Outlined.PhotoCameraFront
        SourceType.SCREEN -> Icons.Outlined.ScreenShare
        SourceType.REMOTE -> Icons.Outlined.Devices
        SourceType.TEXT -> Icons.Outlined.TextFields
        SourceType.IMAGE -> Icons.Outlined.Image
        SourceType.TEST -> Icons.Outlined.Tune
    }
    val statusColor = when (status) {
        SourceStatus.AKTIF -> Berhasil
        SourceStatus.SIAGA -> TeksSekunder
        SourceStatus.TERPUTUS -> Peringatan
        SourceStatus.GALAT -> Rekam
    }
    val statusLabel = when (status) {
        SourceStatus.AKTIF -> stringResource(R.string.source_status_active)
        SourceStatus.SIAGA -> stringResource(R.string.source_status_standby)
        SourceStatus.TERPUTUS -> stringResource(R.string.source_status_disconnected)
        SourceStatus.GALAT -> stringResource(R.string.source_status_error)
    }

    Surface(
        color = if (selected) Color(0xFFE7EFF8) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onSelect() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Aksen else TeksSekunder,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (source.visible) Teks else TeksSekunder,
                )
                Text(
                    text = "$statusLabel • ${(source.transform.width * 100).roundToInt()}% x ${(source.transform.height * 100).roundToInt()}%",
                    fontSize = 11.sp,
                    color = statusColor,
                )
            }

            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.source_layer_up),
                    tint = if (canMoveUp) Teks else Garis,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.source_layer_down),
                    tint = if (canMoveDown) Teks else Garis,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (source.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(
                        if (source.visible) R.string.source_hidden else R.string.source_visible,
                    ),
                    tint = if (source.visible) Aksen else TeksSekunder,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.source_remove),
                    tint = Rekam,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddSourceOptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Aksen,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Teks,
            )
        }
    }
}
