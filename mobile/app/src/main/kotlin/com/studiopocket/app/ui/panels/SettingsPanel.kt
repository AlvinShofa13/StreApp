package com.studiopocket.app.ui.panels

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.studiopocket.app.R
import com.studiopocket.app.engine.AppEngine
import com.studiopocket.app.engine.EngineState
import com.studiopocket.app.engine.RecordingResolution
import com.studiopocket.app.engine.StorageOption
import com.studiopocket.app.ui.theme.Aksen
import com.studiopocket.app.ui.theme.Berhasil
import com.studiopocket.app.ui.theme.Garis
import com.studiopocket.app.ui.theme.Kartu
import com.studiopocket.app.ui.theme.Peringatan
import com.studiopocket.app.ui.theme.Rekam
import com.studiopocket.app.ui.theme.Teks
import com.studiopocket.app.ui.theme.TeksSekunder
import com.studiopocket.core.thermal.LoadEstimator
import com.studiopocket.core.thermal.LoadLevel
import com.studiopocket.model.Presets
import com.studiopocket.model.SceneTransition
import com.studiopocket.model.SourceType
import com.studiopocket.source.remote.NetworkLinkKind

/**
 * Panel Pengaturan OBS Mobile:
 * Estimasi Beban, Preset Kualitas, Resolusi/FPS, Orientasi Kanvas, Transisi, Penyimpanan, dan Camera Node QR.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPanel(
    state: EngineState,
    engine: AppEngine,
    appVersion: String = "1.1.1",
    onOpenCameraNodeScanner: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeSources = state.project.activeScene?.sources.orEmpty().filter { it.visible }
    val loadEstimate = remember(activeSources, state.recordingResolution) {
        LoadEstimator.estimasi(
            jumlahUvc = activeSources.count { it.type == SourceType.UVC },
            jumlahKamera = activeSources.count { it.type == SourceType.CAMERA },
            jumlahLayar = activeSources.count { it.type == SourceType.SCREEN },
            jumlahNode = activeSources.count { it.type == SourceType.REMOTE },
            jumlahTest = activeSources.count { it.type == SourceType.TEST },
            lebar = state.recordingResolution.width,
            fps = state.recordingResolution.fps,
        )
    }

    val loadColor = when (loadEstimate.level) {
        LoadLevel.RINGAN -> Berhasil
        LoadLevel.SEDANG -> Aksen
        LoadLevel.BERAT -> Peringatan
        LoadLevel.MAKSIMUM -> Rekam
    }
    val loadLabel = when (loadEstimate.level) {
        LoadLevel.RINGAN -> stringResource(R.string.load_light)
        LoadLevel.SEDANG -> stringResource(R.string.load_medium)
        LoadLevel.BERAT -> stringResource(R.string.load_heavy)
        LoadLevel.MAKSIMUM -> stringResource(R.string.load_max)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Kartu Estimasi Beban (PRD FR-7.3)
        Surface(
            color = loadColor.copy(alpha = 0.10f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, loadColor.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = loadLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = loadColor,
                )
                if (loadEstimate.saranHemat) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.load_warning_hint),
                        fontSize = 11.sp,
                        color = Teks,
                    )
                }
            }
        }

        // 2. Preset Kualitas & Resolusi/FPS & Orientasi Kanvas
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
                Text(
                    text = stringResource(R.string.settings_preset),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )

                val presets = listOf(
                    Presets.HEMAT.id to R.string.preset_hemat,
                    Presets.SEIMBANG.id to R.string.preset_seimbang,
                    Presets.TINGGI.id to R.string.preset_tinggi,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { (presetId, labelRes) ->
                        FilterChip(
                            selected = state.project.activePresetId == presetId,
                            onClick = { if (!state.recording) engine.setPreset(presetId) },
                            enabled = !state.recording,
                            label = { Text(stringResource(labelRes), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE7EFF8),
                                selectedLabelColor = Aksen,
                            ),
                        )
                    }
                }

                if (state.recording) {
                    Text(
                        text = stringResource(R.string.settings_locked_recording),
                        fontSize = 11.sp,
                        color = Peringatan,
                    )
                }

                Text(
                    text = stringResource(R.string.settings_resolution),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )
                RecordingResolution.entries.forEach { res ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = state.recordingResolution == res,
                                enabled = !state.recording,
                                onClick = { engine.setRecordingResolution(res) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.recordingResolution == res,
                            enabled = !state.recording,
                            onClick = { engine.setRecordingResolution(res) },
                            colors = RadioButtonDefaults.colors(selectedColor = Aksen),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(res.labelRes),
                            fontSize = 13.sp,
                            color = if (state.recording) TeksSekunder else Teks,
                        )
                    }
                }

                // Orientasi Kanvas Output (Lanskap 16:9 / Potret 9:16)
                Text(
                    text = stringResource(R.string.settings_orientation),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = !state.canvasPortrait,
                        enabled = !state.recording,
                        onClick = { engine.setCanvasPortrait(false) },
                        label = { Text(stringResource(R.string.canvas_orientation_landscape), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                    )
                    FilterChip(
                        selected = state.canvasPortrait,
                        enabled = !state.recording,
                        onClick = { engine.setCanvasPortrait(true) },
                        label = { Text(stringResource(R.string.canvas_orientation_portrait), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                    )
                }

                // Transisi Antar Scene (PRD FR-8.3)
                Text(
                    text = stringResource(R.string.settings_transition),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.project.transition == SceneTransition.CUT,
                        onClick = { engine.setTransition(SceneTransition.CUT, 0) },
                        label = { Text(stringResource(R.string.transition_cut), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                    )
                    FilterChip(
                        selected = state.project.transition == SceneTransition.FADE,
                        onClick = { engine.setTransition(SceneTransition.FADE, 300) },
                        label = { Text(stringResource(R.string.transition_fade), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                    )
                }

                // Mode Pratinjau Hemat Daya (15 fps)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_preview_power),
                        fontSize = 13.sp,
                        color = Teks,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.previewLowPower,
                        onCheckedChange = { engine.setPreviewLowPower(it) },
                    )
                }
            }
        }

        // 3. Lokasi Penyimpanan Rekaman
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Kartu),
            border = BorderStroke(1.dp, Garis),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.settings_storage_location),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )
                Spacer(modifier = Modifier.height(6.dp))
                StorageOption.entries.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = state.storageOption == opt,
                                onClick = { engine.setStorageOption(opt) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.storageOption == opt,
                            onClick = { engine.setStorageOption(opt) },
                            colors = RadioButtonDefaults.colors(selectedColor = Aksen),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(opt.labelRes),
                            fontSize = 13.sp,
                            color = Teks,
                        )
                    }
                }
            }
        }

        // 4. Mode Dua Ponsel (Camera Node Pairing QR - PRD 7.6)
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
                Text(
                    text = stringResource(R.string.remote_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Aksen,
                )

                val nodesText = if (state.remoteNodes.isEmpty()) {
                    stringResource(R.string.remote_no_nodes)
                } else {
                    stringResource(R.string.remote_connected_nodes, state.remoteNodes.joinToString(", "))
                }
                Text(text = nodesText, fontSize = 12.sp, color = TeksSekunder)
                Text(
                    text = stringResource(R.string.remote_usb_cable_hint),
                    fontSize = 11.sp,
                    color = TeksSekunder,
                )

                val pairing = state.remotePairing
                if (!state.remoteErrorDetail.isNullOrBlank()) {
                    Surface(
                        color = Rekam.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Rekam.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${stringResource(R.string.remote_error_start)} (${state.remoteErrorDetail})",
                            fontSize = 11.sp,
                            color = Rekam,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                if (pairing == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { engine.startRemoteHost() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Aksen),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.remote_start_host),
                                color = Aksen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenCameraNodeScanner,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Berhasil),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.remote_scan_node),
                                color = Berhasil,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    var showQrDialog by remember(pairing.token) { mutableStateOf(false) }
                    var manualIpInput by remember(pairing.host) { mutableStateOf(pairing.host) }

                    Text(
                        text = stringResource(R.string.remote_hint),
                        fontSize = 11.sp,
                        color = TeksSekunder,
                    )
                    // Pemilih jalur jaringan (Kabel USB Tethering vs Wi-Fi/Hotspot)
                    if (state.remoteEndpoints.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.remoteEndpoints.forEach { ep ->
                                val kindText = when (ep.kind) {
                                    NetworkLinkKind.USB_CABLE -> stringResource(R.string.remote_link_usb)
                                    NetworkLinkKind.WIFI_HOTSPOT -> stringResource(R.string.remote_link_wifi)
                                    NetworkLinkKind.OTHER -> ep.interfaceName
                                }
                                FilterChip(
                                    selected = pairing.host == ep.ip,
                                    onClick = { engine.selectRemoteHostIp(ep.ip) },
                                    label = { Text("$kindText (${ep.ip})", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFE7EFF8),
                                        selectedLabelColor = Aksen,
                                    ),
                                )
                            }
                        }
                    }
                    val qrBitmap = remember(pairing) { generateQrBitmap(pairing.toUri(), 480) }
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.remote_title),
                            modifier = Modifier
                                .size(200.dp)
                                .align(Alignment.CenterHorizontally),
                        )
                        OutlinedButton(
                            onClick = { showQrDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Aksen),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.remote_enlarge_qr),
                                fontSize = 12.sp,
                                color = Aksen,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.remote_qr_generate_failed),
                            fontSize = 11.sp,
                            color = Peringatan,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    Text(
                        text = "${pairing.host}:${pairing.port} • Token: ${pairing.token}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Teks,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (pairing.webPort > 0) {
                        Text(
                            text = stringResource(R.string.remote_web_bridge_url_hint, pairing.webBridgeUrl()),
                            fontSize = 11.sp,
                            color = Aksen,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    // Input IP Host manual jika OEM menyembunyikan IP hotspot (mis. 192.168.x.1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = manualIpInput,
                            onValueChange = { manualIpInput = it },
                            label = { Text(stringResource(R.string.remote_manual_ip_hint), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                val trimmed = manualIpInput.trim()
                                if (trimmed.isNotEmpty()) engine.selectRemoteHostIp(trimmed)
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Aksen),
                            modifier = Modifier.height(52.dp),
                        ) {
                            Text(stringResource(R.string.remote_apply_ip), fontSize = 12.sp, color = Aksen)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { engine.rotateRemoteToken() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.remote_rotate_token), fontSize = 12.sp, color = Teks)
                        }
                        OutlinedButton(
                            onClick = { engine.stopRemoteHost() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Rekam),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(stringResource(R.string.remote_stop_host), fontSize = 12.sp, color = Rekam)
                        }
                    }

                    if (showQrDialog && qrBitmap != null) {
                        AlertDialog(
                            onDismissRequest = { showQrDialog = false },
                            title = {
                                Text(
                                    text = stringResource(R.string.remote_qr_dialog_title),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Teks,
                                )
                            },
                            text = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.remote_qr_dialog_title),
                                        modifier = Modifier.size(260.dp),
                                    )
                                    Text(
                                        text = "${pairing.host}:${pairing.port}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Teks,
                                    )
                                    Text(
                                        text = "Token: ${pairing.token}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Aksen,
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showQrDialog = false }) {
                                    Text(stringResource(R.string.settings_close), color = Aksen, fontWeight = FontWeight.Bold)
                                }
                            },
                            containerColor = Kartu,
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }
        }

        // Info Versi Aplikasi
        Text(
            text = stringResource(R.string.settings_app_version, appVersion),
            fontSize = 11.sp,
            color = TeksSekunder,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 4.dp),
        )
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bmp
    } catch (_: Throwable) {
        null
    }
}
