package com.rhp.mediaplayer.ui.queue

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.coverart.CoverArtRepository
import com.rhp.mediaplayer.model.LoopMode
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.ui.components.IconAction
import com.rhp.mediaplayer.ui.components.ScrollableList
import com.rhp.mediaplayer.ui.formatDuration
import com.rhp.mediaplayer.ui.icons.AppIcons

/**
 * What is playing and what comes next, kept beside the playlist rather than
 * replacing it.
 *
 * The main list always shows the playlist in name order, so this panel is the
 * only place the *actual* play order is visible. It is the direct answer to
 * "why did it play that next?" -- with shuffle on, the answer is right here,
 * and the "已播放" section doubles as the history that the previous button
 * walks back through.
 */
@Composable
fun QueuePanel(
    nowPlaying: Song?,
    playedInPass: List<Song>,
    upNext: List<Song>,
    shuffle: Boolean,
    loop: LoopMode,
    playbackStarted: Boolean,
    coverArt: CoverArtRepository,
    onSelect: (String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "播放队列", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = describeMode(shuffle, loop),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconAction(
                icon = AppIcons.ChevronRight,
                contentDescription = "收起播放队列",
                tooltip = "收起播放队列",
                onClick = onCollapse,
                size = 32,
            )
        }

        val queueListState = rememberLazyListState()

        ScrollableList(
            state = queueListState,
            contentPadding = PaddingValues(start = 8.dp, end = 20.dp, bottom = 16.dp),
        ) {
            if (playedInPass.isNotEmpty()) {
                item(key = "header-played", contentType = "header") {
                    Header("已播放 · ${playedInPass.size}")
                }
                items(
                    items = playedInPass,
                    key = { "played-${it.id}" },
                    contentType = { "row" },
                ) { song ->
                    // Dimmed: these are behind the playhead.
                    QueueRow(
                        song = song,
                        coverArt = coverArt,
                        tone = RowTone.Played,
                        onClick = { onSelect(song.id) },
                    )
                }
            }

            item(key = "header-now", contentType = "header") {
                // Until the user presses play, the first track is merely cued.
                Header(if (playbackStarted) "正在播放" else "即将播放")
            }

            if (nowPlaying == null) {
                item(key = "empty-now", contentType = "empty") {
                    Text(
                        text = "这个歌单还没有可以播放的歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else {
                item(key = "now", contentType = "row") {
                    QueueRow(
                        song = nowPlaying,
                        coverArt = coverArt,
                        tone = RowTone.Current,
                        onClick = { onSelect(nowPlaying.id) },
                    )
                }
            }

            if (upNext.isNotEmpty()) {
                item(key = "header-next", contentType = "header") {
                    Header("接下来 · ${upNext.size}")
                }
                items(
                    items = upNext,
                    key = { "next-${it.id}" },
                    contentType = { "row" },
                ) { song ->
                    QueueRow(
                        song = song,
                        coverArt = coverArt,
                        tone = RowTone.Upcoming,
                        onClick = { onSelect(song.id) },
                    )
                }
            } else if (nowPlaying != null) {
                item(key = "end-of-pass", contentType = "empty") {
                    Text(
                        text = if (loop == LoopMode.Off) {
                            "本轮已到末尾，播放将在此停止"
                        } else {
                            "本轮已到末尾，将开始新一轮"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun describeMode(shuffle: Boolean, loop: LoopMode): String {
    val order = if (shuffle) "随机" else "顺序"
    val repeat = when (loop) {
        LoopMode.Off -> "不循环"
        LoopMode.All -> "列表循环"
        LoopMode.One -> "单曲循环"
    }
    return "$order · $repeat"
}

private enum class RowTone { Played, Current, Upcoming }

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueRow(
    song: Song,
    coverArt: CoverArtRepository,
    tone: RowTone,
    onClick: () -> Unit,
) {
    val artwork: ImageBitmap? = coverArt.get(song.id)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val background = when {
        tone == RowTone.Current -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val titleColor = when (tone) {
        RowTone.Current -> MaterialTheme.colorScheme.primary
        RowTone.Played -> MaterialTheme.colorScheme.onSurfaceVariant
        RowTone.Upcoming -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
