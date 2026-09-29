package com.rhp.mediaplayer.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A draggable scrollbar for a lazy list.
 *
 * Scrollbars are desktop-only API, so this sits behind expect/actual to keep the
 * screens in `commonMain`. Without one, finding a song in a few thousand means
 * wheel-scrolling until it goes past -- which is not a way to find anything.
 *
 * The implementation is also expected to restyle the bar to the current theme:
 * the platform default draws a fairly assertive track, which on a dark theme
 * reads as yet another hard edge.
 */
@Composable
expect fun AppVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
)
