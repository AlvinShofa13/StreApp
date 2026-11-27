package com.studiopocket.app.ui.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiopocket.app.ChatController
import com.studiopocket.app.R
import com.studiopocket.app.engine.AppEngine
import com.studiopocket.app.engine.EngineState
import com.studiopocket.app.engine.StreamProfileStore
import com.studiopocket.app.ui.theme.Aksen
import com.studiopocket.app.ui.theme.Berhasil
import com.studiopocket.app.ui.theme.Garis
import com.studiopocket.app.ui.theme.Kartu
import com.studiopocket.app.ui.theme.Peringatan
import com.studiopocket.app.ui.theme.Rekam
import com.studiopocket.app.ui.theme.Teks
import com.studiopocket.app.ui.theme.TeksSekunder
import com.studiopocket.chat.ChatConnection
import com.studiopocket.chat.ChatRole
import com.studiopocket.chat.FakeChatSource
import com.studiopocket.chat.TwitchChatSource
import com.studiopocket.model.Project
import com.studiopocket.stream.RtmpPublisher
import com.studiopocket.stream.StreamPlatform
import com.studiopocket.stream.StreamProfile
import kotlinx.coroutines.launch

/**
 * Panel Siaran RTMP/RTMPS & Chat Langsung (PRD 7.10 & 7.11).
 * Chat murni baca-saja di layar pengguna, terpisah total dari GlCompositor.
 */
@Composable
fun StreamChatPanel(
    state: EngineState,
    engine: AppEngine,
    profileStore: StreamProfileStore,
    chatController: ChatController,
    modifier: Modifier = Modifier,
) {
    var profiles by remember { mutableStateOf(profileStore.list()) }
    var selectedProfileId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
    var showAddProfile by remember { mutableStateOf(false) }

    val messages by chatController.messages.collectAsState()
    val chatConn by chatController.connection.collectAsState()
    var filterBots by remember { mutableStateOf(chatController.filterBots) }
    var filterCmds by remember { mutableStateOf(chatController.filterCommands) }

    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Kartu Profil & Kontrol Siaran RTMP
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.stream_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teks,
                    )
                    OutlinedButton(
                        onClick = { showAddProfile = true },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Garis),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Aksen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.stream_add_profile), fontSize = 11.sp, color = Aksen)
                    }
                }

                // Status bar siaran
                val statusLabel = when (state.stream.status) {
                    RtmpPublisher.Status.SIARAN -> stringResource(R.string.stream_status_live, state.stream.kbps)
                    RtmpPublisher.Status.MENYAMBUNG -> stringResource(R.string.stream_status_connecting)
                    else -> stringResource(R.string.stream_status_idle)
                }
                val statusColor = when (state.stream.status) {
                    RtmpPublisher.Status.SIARAN -> Rekam
                    RtmpPublisher.Status.MENYAMBUNG -> Peringatan
                    else -> Berhasil
                }
                Surface(
                    color = statusColor.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                if (profiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stream_no_profiles),
                        fontSize = 12.sp,
                        color = TeksSekunder,
                    )
                } else {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { selectedProfileId = profile.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = profile.id == selectedProfileId,
                                onClick = { selectedProfileId = profile.id },
                                colors = RadioButtonDefaults.colors(selectedColor = Aksen),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${profile.platform.name} • ${profile.name}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Teks,
                                    )
                                    if (!profile.ready) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = Peringatan.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.stream_not_ready_badge),
                                                fontSize = 10.sp,
                                                color = Peringatan,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                if (profile.channel.isNotBlank()) {
                                    Text(
                                        text = profile.channel,
                                        fontSize = 11.sp,
                                        color = TeksSekunder,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    profileStore.delete(profile.id)
                                    profiles = profileStore.list()
                                    if (selectedProfileId == profile.id) {
                                        selectedProfileId = profiles.firstOrNull()?.id
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = Rekam,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (state.stream.active) {
                                engine.stopStream()
                            } else if (selectedProfile != null) {
                                val key = profileStore.streamKey(selectedProfile.id).orEmpty()
                                engine.startStream(selectedProfile, key)
                            }
                        },
                        enabled = state.stream.active || (selectedProfile != null && selectedProfile.ready),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.stream.active) Rekam else Aksen,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Icon(
                            imageVector = if (state.stream.active) Icons.Outlined.Stop else Icons.Outlined.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (state.stream.active) R.string.stream_stop else R.string.stream_start,
                            ),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // 2. Panel Chat Langsung (Baca-saja, hanya terlihat oleh pengguna)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.chat_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Teks,
                    )
                    Text(
                        text = chatConn.name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (chatConn) {
                            ChatConnection.TERSAMBUNG -> Berhasil
                            ChatConnection.MENYAMBUNG -> Peringatan
                            ChatConnection.GALAT -> Rekam
                            else -> TeksSekunder
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.chat_note_private),
                    fontSize = 11.sp,
                    color = TeksSekunder,
                )

                if (selectedProfile?.platform == StreamPlatform.TIKTOK) {
                    Text(
                        text = stringResource(R.string.chat_tiktok_unavailable),
                        fontSize = 11.sp,
                        color = Peringatan,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (chatConn == ChatConnection.TERPUTUS || chatConn == ChatConnection.GALAT) {
                        if (selectedProfile?.platform == StreamPlatform.TWITCH && selectedProfile.channel.isNotBlank()) {
                            OutlinedButton(
                                onClick = { chatController.start(TwitchChatSource(selectedProfile.channel)) },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Aksen),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.chat_connect_twitch), fontSize = 11.sp, color = Aksen)
                            }
                        }
                        OutlinedButton(
                            onClick = { chatController.start(FakeChatSource(messagesPerSecond = 2)) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Garis),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.chat_connect_demo), fontSize = 11.sp, color = Teks)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { chatController.stop() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Rekam),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.chat_disconnect), fontSize = 11.sp, color = Rekam)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filterBots,
                        onClick = {
                            filterBots = !filterBots
                            chatController.filterBots = filterBots
                        },
                        label = { Text(stringResource(R.string.chat_filter_bots), fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                    )
                    FilterChip(
                        selected = filterCmds,
                        onClick = {
                            filterCmds = !filterCmds
                            chatController.filterCommands = filterCmds
                        },
                        label = { Text(stringResource(R.string.chat_filter_cmds), fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE7EFF8),
                            selectedLabelColor = Aksen,
                        ),
                    )
                }

                HorizontalDivider(color = Garis)

                val listState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                val isAtBottom = !listState.canScrollForward

                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty() && isAtBottom) {
                        listState.scrollToItem(messages.lastIndex)
                    }
                }

                if (messages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_empty),
                        fontSize = 12.sp,
                        color = TeksSekunder,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (msg.role == ChatRole.MODERATOR || msg.role == ChatRole.PEMILIK) {
                                    Surface(
                                        color = Aksen.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.padding(end = 6.dp),
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (msg.role == ChatRole.MODERATOR) R.string.chat_role_mod else R.string.chat_role_owner,
                                            ),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Aksen,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "${msg.author}: ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Teks,
                                )
                                Text(
                                    text = msg.text,
                                    fontSize = 12.sp,
                                    color = TeksSekunder,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    if (!isAtBottom) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.chat_scroll_latest), fontSize = 12.sp, color = Aksen)
                        }
                    }
                }
            }
        }
    }

    if (showAddProfile) {
        AddStreamProfileDialog(
            onDismiss = { showAddProfile = false },
            onSave = { profile, key ->
                profileStore.save(profile, key)
                profiles = profileStore.list()
                selectedProfileId = profile.id
                showAddProfile = false
            },
        )
    }
}

@Composable
private fun AddStreamProfileDialog(
    onDismiss: () -> Unit,
    onSave: (StreamProfile, String) -> Unit,
) {
    var platform by remember { mutableStateOf(StreamPlatform.YOUTUBE) }
    var name by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_add_profile), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.stream_platform), fontSize = 12.sp, color = TeksSekunder)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StreamPlatform.entries.forEach { p ->
                        FilterChip(
                            selected = platform == p,
                            onClick = { platform = p },
                            label = { Text(p.name, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE7EFF8),
                                selectedLabelColor = Aksen,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.stream_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text(stringResource(R.string.stream_channel)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.stream_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.stream_ready_toggle),
                        fontSize = 12.sp,
                        color = Teks,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = ready, onCheckedChange = { ready = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim().ifBlank { platform.name }
                    val profile = StreamProfile(
                        id = Project.newId(),
                        platform = platform,
                        name = trimmed,
                        channel = channel.trim(),
                        ready = ready,
                    )
                    onSave(profile, key.trim())
                },
            ) {
                Text(stringResource(R.string.action_save), color = Aksen, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = TeksSekunder)
            }
        },
        containerColor = Kartu,
        shape = RoundedCornerShape(16.dp),
    )
}
