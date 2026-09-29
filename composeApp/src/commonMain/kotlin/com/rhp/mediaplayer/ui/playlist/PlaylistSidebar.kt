package com.rhp.mediaplayer.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.model.Playlist
import com.rhp.mediaplayer.ui.components.IconAction
import com.rhp.mediaplayer.ui.components.ScrollableList
import com.rhp.mediaplayer.ui.components.SectionLabel
import com.rhp.mediaplayer.ui.icons.AppIcons

/** Which dialog, if any, the sidebar has open. */
private sealed interface SidebarDialog {
    data object None : SidebarDialog
    data object Create : SidebarDialog
    data class Rename(val playlist: Playlist) : SidebarDialog
}

@Composable
fun PlaylistSidebar(
    playlists: List<Playlist>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    queuePanelExpanded: Boolean,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog: SidebarDialog by remember { mutableStateOf(SidebarDialog.None) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 18.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "音乐库",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = AppIcons.Plus,
                contentDescription = "新建歌单",
                tooltip = "新建歌单",
                onClick = { dialog = SidebarDialog.Create },
                size = 32,
            )
        }

        SectionLabel("歌单", modifier = Modifier.padding(start = 14.dp))

        val playlistListState = rememberLazyListState()

        ScrollableList(
            state = playlistListState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        ) {
            items(items = playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    selected = playlist.id == selectedId,
                    onSelect = { onSelect(playlist.id) },
                    onRename = { dialog = SidebarDialog.Rename(playlist) },
                    onDelete = { onDelete(playlist.id) },
                )
            }
        }

        // No separator above the footer: the panel is one surface, and the
        // footer's own padding is enough to read it as a separate cluster.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconAction(
                icon = if (darkTheme) AppIcons.Sun else AppIcons.Moon,
                contentDescription = if (darkTheme) "切换到浅色模式" else "切换到深色模式",
                tooltip = if (darkTheme) "切换到浅色模式" else "切换到深色模式",
                onClick = onToggleTheme,
                size = 34,
            )
            Spacer(Modifier.weight(1f))
            if (!queuePanelExpanded) {
                IconAction(
                    icon = AppIcons.Queue,
                    contentDescription = "展开播放队列",
                    tooltip = "展开播放队列",
                    onClick = onToggleQueue,
                    size = 34,
                )
            }
        }
    }

    when (val current = dialog) {
        SidebarDialog.None -> Unit

        SidebarDialog.Create -> PlaylistNameDialog(
            title = "新建歌单",
            initialName = "",
            confirmLabel = "创建",
            onDismiss = { dialog = SidebarDialog.None },
            onConfirm = { name ->
                onCreate(name)
                dialog = SidebarDialog.None
            },
        )

        is SidebarDialog.Rename -> PlaylistNameDialog(
            title = "重命名歌单",
            initialName = current.playlist.name,
            confirmLabel = "保存",
            onDismiss = { dialog = SidebarDialog.None },
            onConfirm = { name ->
                onRename(current.playlist.id, name)
                dialog = SidebarDialog.None
            },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else if (hovered) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Queue,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(17.dp),
        )

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songs.size} 首",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hovered) {
            IconAction(
                icon = AppIcons.Edit,
                contentDescription = "重命名",
                tooltip = "重命名歌单",
                onClick = onRename,
                size = 28,
            )
            IconAction(
                icon = AppIcons.Delete,
                contentDescription = "删除歌单",
                tooltip = "删除歌单",
                onClick = onDelete,
                size = 28,
            )
        }
    }
}

/**
 * A one-field dialog for both creating and renaming.
 *
 * The text field grabs focus on open so the user can just start typing, which
 * matters when the only thing the dialog does is collect a name.
 */
@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("歌单名称") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
