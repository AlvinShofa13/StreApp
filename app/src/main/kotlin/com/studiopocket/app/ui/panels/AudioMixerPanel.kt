package com.studiopocket.app.ui.panels

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.studiopocket.core.audio.AudioEngine
import com.studiopocket.core.audio.MonitorMode
import kotlin.math.roundToInt

/**
 * Panel Mixer Audio ala OBS Mobile:
 * Fader volume, mute, level meter real-time, 3 mode monitoring, dan peringatan umpan balik.
 */
@Composable
fun AudioMixerPanel(
    state: EngineState,
    engine: AppEngine,
    modifier: Modifier = Modifier,
) {
    val channels = state.audioChannels
    val anyMonitoring = channels.any { it.monitor != MonitorMode.MATI }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Peringatan umpan balik speaker (PRD FR-4.3)
        if (anyMonitoring && !state.headphones) {
            Surface(
                color = Peringatan.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Peringatan.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = Peringatan,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.mixer_feedback_warning),
                        fontSize = 12.sp,
                        color = Teks,
                    )
                }
            }
        } else if (state.headphones) {
            Surface(
                color = Berhasil.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Headphones,
                        contentDescription = null,
                        tint = Berhasil,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.mixer_headphones_connected),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Berhasil,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Kartu),
            border = BorderStroke(1.dp, Garis),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mixer_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teks,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { engine.addMicInput() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(15.dp), tint = Aksen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.mixer_add_mic), fontSize = 11.sp, color = Aksen)
                        }
                        OutlinedButton(
                            onClick = { engine.addInternalAudio() },
                            enabled = state.hasScreen,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.mixer_add_internal),
                                fontSize = 11.sp,
                                color = if (state.hasScreen) Aksen else TeksSekunder,
                            )
                        }
                    }
                }

                if (!state.hasScreen) {
                    Text(
                        text = stringResource(R.string.mixer_internal_unavailable),
                        fontSize = 11.sp,
                        color = TeksSekunder,
                    )
                }

                if (channels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mixer_empty),
                        fontSize = 12.sp,
                        color = TeksSekunder,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    channels.forEachIndexed { idx, ch ->
                        AudioChannelControl(
                            channel = ch,
                            level = state.levels[ch.id] ?: 0f,
                            canRemove = channels.size > 1,
                            onGainChange = { engine.setGain(ch.id, it) },
                            onToggleMute = { engine.setMuted(ch.id, !ch.muted) },
                            onMonitorChange = { engine.setMonitor(ch.id, it) },
                            onRemove = { engine.removeAudioChannel(ch.id) },
                        )
                        if (idx < channels.lastIndex) {
                            HorizontalDivider(color = Garis, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioChannelControl(
    channel: AudioEngine.ChannelInfo,
    level: Float,
    canRemove: Boolean,
    onGainChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onMonitorChange: (MonitorMode) -> Unit,
    onRemove: () -> Unit,
) {
    val meterColor = when {
        channel.muted -> Garis
        level > 0.85f -> Rekam
        level > 0.65f -> Peringatan
        else -> Berhasil
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (channel.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    contentDescription = stringResource(
                        if (channel.muted) R.string.mixer_unmute else R.string.mixer_mute,
                    ),
                    tint = if (channel.muted) Rekam else Aksen,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (channel.muted) TeksSekunder else Teks,
                )
                Text(
                    text = "${(channel.gain * 100).roundToInt()}%",
                    fontSize = 11.sp,
                    color = TeksSekunder,
                )
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = Rekam,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Level meter
        LinearProgressIndicator(
            progress = { if (channel.muted) 0f else level.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = meterColor,
            trackColor = Garis,
        )

        // Gain fader (0..200%)
        Slider(
            value = channel.gain,
            onValueChange = onGainChange,
            valueRange = 0f..2f,
            enabled = !channel.muted,
            colors = SliderDefaults.colors(thumbColor = Aksen, activeTrackColor = Aksen),
            modifier = Modifier.fillMaxWidth(),
        )

        // Tiga mode monitoring (PRD FR-4.2)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val modes = listOf(
                MonitorMode.MATI to R.string.mixer_monitor_off,
                MonitorMode.HANYA_MONITOR to R.string.mixer_monitor_only,
                MonitorMode.MONITOR_DAN_REKAM to R.string.mixer_monitor_and_rec,
            )
            modes.forEach { (mode, labelRes) ->
                FilterChip(
                    selected = channel.monitor == mode,
                    onClick = { onMonitorChange(mode) },
                    label = { Text(stringResource(labelRes), fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE7EFF8),
                        selectedLabelColor = Aksen,
                    ),
                )
            }
        }
    }
}
