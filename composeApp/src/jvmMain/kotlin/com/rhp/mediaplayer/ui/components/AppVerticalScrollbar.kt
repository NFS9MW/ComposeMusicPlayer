package com.rhp.mediaplayer.ui.components

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Desktop scrollbar, styled to the app's theme.
 *
 * The default track is opaque enough to look like a panel edge rather than a
 * control, which fights with the rest of the window. Deriving both colours from
 * `onSurface` at low alpha keeps the bar legible against the panel it sits on in
 * either theme, instead of being tuned for one of them.
 */
@Composable
actual fun AppVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    val style = defaultScrollbarStyle().copy(
        thickness = 8.dp,
        // A short thumb is hard to grab; this keeps it usable at the top of a
        // very long list.
        minimalHeight = 28.dp,
        shape = RoundedCornerShape(4.dp),
        unhoverColor = onSurface.copy(alpha = 0.14f),
        hoverColor = onSurface.copy(alpha = 0.34f),
    )

    CompositionLocalProvider(LocalScrollbarStyle provides style) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = modifier,
        )
    }
}
