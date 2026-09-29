package com.rhp.mediaplayer.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.ui.formatDuration

/**
 * Elapsed time, a seek bar and total time.
 *
 * Shared by the transport bar and the now-playing screen, which differ only in
 * how large they draw it. The logic is worth keeping in one place: **while
 * dragging, the local value wins**. The provider is not called at all during a
 * drag, so the playhead cannot fight the pointer, and this composable stops
 * subscribing to the position ticker for as long as the drag lasts.
 *
 * [position] arrives as a provider rather than a value so that only this row --
 * not the bar or the screen around it -- recomposes when playback advances.
 */
@Composable
fun ProgressRow(
    song: Song?,
    positionMsProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    gap: Dp = 10.dp,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val durationMs = song?.durationMs?.takeIf { it > 0 } ?: 0L
    val positionMs = dragFraction?.let { (it * durationMs).toLong() } ?: positionMsProvider()

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            // 0:00 rather than the unknown-duration placeholder: once the track
            // length is known, standing at the start is a real position.
            text = if (durationMs > 0) formatDuration(positionMs.coerceAtLeast(0L)) else "--:--",
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PlayerSlider(
            value = if (durationMs > 0) {
                (dragFraction ?: (positionMs.toFloat() / durationMs)).coerceIn(0f, 1f)
            } else {
                0f
            },
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                val fraction = dragFraction
                dragFraction = null
                if (fraction != null && durationMs > 0) {
                    onSeek((fraction * durationMs).toLong())
                }
            },
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f).padding(horizontal = gap),
        )

        Text(
            text = formatDuration(song?.durationMs),
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
