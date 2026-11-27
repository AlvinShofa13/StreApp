package com.studiopocket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiopocket.app.R
import com.studiopocket.app.ui.theme.Aksen
import com.studiopocket.app.ui.theme.Garis
import com.studiopocket.app.ui.theme.Kartu
import com.studiopocket.app.ui.theme.Rekam
import com.studiopocket.app.ui.theme.Teks
import com.studiopocket.app.ui.theme.TeksSekunder
import com.studiopocket.model.Scene

/**
 * Deretan kartu scene geser horizontal ala OBS Mobile (1 ketukan pindah scene).
 */
@Composable
fun SceneStrip(
    scenes: List<Scene>,
    activeSceneId: String?,
    onSelectScene: (String) -> Unit,
    onAddScene: (String) -> Unit,
    onRenameScene: (String, String) -> Unit,
    onDuplicateScene: (String, String) -> Unit,
    onRemoveScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuSceneId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Scene?>(null) }
    var renameInput by remember { mutableStateOf("") }

    val nextSceneName = stringResource(R.string.scene_new_name, scenes.size + 1)

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(scenes, key = { it.id }) { scene ->
            val isActive = scene.id == activeSceneId
            val copySuffix = stringResource(R.string.scene_copy_suffix, scene.name)

            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isActive) Color(0xFFE7EFF8) else Kartu,
                    border = BorderStroke(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) Aksen else Garis,
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable { onSelectScene(scene.id) },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = scene.name,
                            fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isActive) Aksen else Teks,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clickable { menuSceneId = scene.id },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = scene.name,
                                tint = if (isActive) Aksen else TeksSekunder,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = menuSceneId == scene.id,
                    onDismissRequest = { menuSceneId = null },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scene_rename)) },
                        onClick = {
                            menuSceneId = null
                            renameTarget = scene
                            renameInput = scene.name
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scene_duplicate)) },
                        onClick = {
                            menuSceneId = null
                            onDuplicateScene(scene.id, copySuffix)
                        },
                    )
                    if (scenes.size > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scene_delete), color = Rekam) },
                            onClick = {
                                menuSceneId = null
                                onRemoveScene(scene.id)
                            },
                        )
                    }
                }
            }
        }

        item(key = "add_scene_btn") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Kartu,
                border = BorderStroke(1.dp, Garis),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onAddScene(nextSceneName) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.scene_add),
                        tint = Aksen,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.scene_add),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Aksen,
                    )
                }
            }
        }
    }

    val editing = renameTarget
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.scene_rename), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.scene_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRenameScene(editing.id, renameInput.trim())
                        }
                        renameTarget = null
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Aksen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel), color = TeksSekunder)
                }
            },
            containerColor = Kartu,
            shape = RoundedCornerShape(16.dp),
        )
    }
}
