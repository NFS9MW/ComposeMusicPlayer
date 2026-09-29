package com.rhp.mediaplayer.ui.transport

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.model.LoopMode
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.ui.components.IconAction
import com.rhp.mediaplayer.ui.components.PlayerSlider
import com.rhp.mediaplayer.ui.components.ProgressRow
import com.rhp.mediaplayer.ui.components.TextToggle
import com.rhp.mediaplayer.ui.formatDuration
import com.rhp.mediaplayer.ui.icons.AppIcons

/**
 * The transport, pinned to the bottom.
 *
 * The two values that change continuously -- playback position and volume --
 * arrive as providers rather than as values. That is the difference between
 * twelve recompositions a second of this whole bar and twelve recompositions of
 * a single slider: reading `controller.positionMs` while composing here would
 * invalidate the bar, its artwork and every button with it, every 80 ms.
 */
@Composable
fun TransportBar(
    nowPlaying: Song?,
    isPlaying: Boolean,
    playbackStarted: Boolean,
    largeArt: ImageBitmap?,
    positionMsProvider: () -> Long,
    volumeProvider: () -> Float,
    shuffle: Boolean,
    loop: LoopMode,
    queuePanelExpanded: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleLoop: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NowPlayingInfo(
                song = nowPlaying,
                artwork = largeArt,
                // A track sits under the cursor as soon as a playlist loads,
                // but saying it is "now playing" before the user has pressed
                // anything would contradict the queue panel.
                cued = nowPlaying != null && !playbackStarted && !isPlaying,
                // Null when there is nothing to show, which is also what makes
                // the block un-clickable: a now-playing screen for no track is
                // an empty screen.
                onOpen = onToggleNowPlaying.takeIf { nowPlaying != null },
                modifier = Modifier.width(248.dp),
            )

            Spacer(Modifier.width(20.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconAction(
                        icon = AppIcons.Previous,
                        contentDescription = "上一首",
                        tooltip = "上一首",
                        onClick = onPrevious,
                        enabled = nowPlaying != null,
                        size = 38,
                    )
                    IconAction(
                        icon = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tooltip = if (isPlaying) "暂停（空格）" else "播放（空格）",
                        onClick = onTogglePlay,
                        // Play also works on an empty queue when there is
                        // something to start; the controller decides.
                        size = 48,
                    )
                    IconAction(
                        icon = AppIcons.Next,
                        contentDescription = "下一首",
                        tooltip = "下一首",
                        onClick = onNext,
                        enabled = nowPlaying != null,
                        size = 38,
                    )
                }

                ProgressRow(
                    song = nowPlaying,
                    positionMsProvider = positionMsProvider,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }

            Spacer(Modifier.width(20.dp))

            ModeCluster(
                shuffle = shuffle,
                loop = loop,
                queuePanelExpanded = queuePanelExpanded,
                volumeProvider = volumeProvider,
                onToggleShuffle = onToggleShuffle,
                onCycleLoop = onCycleLoop,
                onToggleQueue = onToggleQueue,
                onVolume = onVolume,
                modifier = Modifier.width(292.dp),
            )
        }
    }
}

/**
 * Cover, title and artist, and the way into the now-playing screen.
 *
 * The whole block is the target rather than just the sleeve: clicking the name
 * of the song you are listening to is the obvious gesture, and a 52 dp square
 * is a needlessly small thing to have to aim at.
 */
@Composable
private fun NowPlayingInfo(
    song: Song?,
    artwork: ImageBitmap?,
    cued: Boolean,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (onOpen == null) {
                    Modifier
                } else {
                    Modifier
                        .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
                        .pointerHoverIcon(PointerIcon.Hand)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
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
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song?.title ?: "未在播放",
                style = MaterialTheme.typography.titleSmall,
                color = if (song != null && !cued) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    song == null -> "从左侧添加音乐开始"
                    cued -> "按播放开始"
                    else -> song.album?.let { "${song.displayArtist} · $it" } ?: song.displayArtist
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModeCluster(
    shuffle: Boolean,
    loop: LoopMode,
    queuePanelExpanded: Boolean,
    volumeProvider: () -> Float,
    onToggleShuffle: () -> Unit,
    onCycleLoop: () -> Unit,
    onToggleQueue: () -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextToggle(
            label = "随机",
            active = shuffle,
            onClick = onToggleShuffle,
        )

        TextToggle(
            label = when (loop) {
                LoopMode.Off -> "不循环"
                LoopMode.All -> "列表循环"
                LoopMode.One -> "单曲循环"
            },
            active = loop != LoopMode.Off,
            onClick = onCycleLoop,
        )

        Spacer(Modifier.weight(1f))

        IconAction(
            icon = AppIcons.Queue,
            contentDescription = if (queuePanelExpanded) "收起播放队列" else "展开播放队列",
            tooltip = if (queuePanelExpanded) "收起播放队列" else "展开播放队列",
            onClick = onToggleQueue,
            active = queuePanelExpanded,
            size = 36,
        )

        VolumeControl(volumeProvider = volumeProvider, onVolume = onVolume)
    }
}

@Composable
private fun VolumeControl(
    volumeProvider: () -> Float,
    onVolume: (Float) -> Unit,
) {
    val volume = volumeProvider()
    val muted = volume <= 0.001f

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            icon = if (muted) AppIcons.VolumeMuted else AppIcons.VolumeUp,
            contentDescription = if (muted) "取消静音" else "静音",
            tooltip = if (muted) "取消静音" else "静音",
            onClick = { onVolume(if (muted) 1f else 0f) },
            size = 34,
        )
        PlayerSlider(
            value = volume.coerceIn(0f, 1f),
            onValueChange = onVolume,
            modifier = Modifier.width(96.dp),
        )
    }
}
