package com.rhp.mediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's one slider: a short handle on a stock Material track.
 *
 * Two departures from [Slider]'s defaults, both of them about desktop use:
 *
 *  * **No 48 dp touch target.** Material's minimum interactive size is right on
 *    a phone and far taller than the bars and panels here. Clearing the local
 *    keeps the track drawing identical while letting the surrounding layout stay
 *    compact.
 *  * **A shorter handle.** Material's is 44 dp tall, which is taller than the
 *    track it rides in, so it reads as a wall across the bar rather than as a
 *    grip. Only the handle is swapped; the track, its colours and the dragging
 *    behaviour are all still Material's.
 *
 * Shared rather than private to one screen because the now-playing view needs
 * exactly the same control as the transport bar, and having two sliders that
 * look subtly different in one window is the kind of thing nobody reports but
 * everybody notices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            thumb = { SliderHandle(enabled = enabled) },
            modifier = modifier,
        )
    }
}

/** The grip: a short rounded bar, comfortably inside the height of the track. */
@Composable
private fun SliderHandle(enabled: Boolean) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
    )
}

private val HANDLE_WIDTH = 4.dp
private val HANDLE_HEIGHT = 15.dp
