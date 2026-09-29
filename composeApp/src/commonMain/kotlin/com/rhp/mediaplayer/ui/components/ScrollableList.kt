package com.rhp.mediaplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A lazy list with a scrollbar in its trailing gutter.
 *
 * Wrapping the two together means a screen gets both or neither: a list long
 * enough to need a scrollbar is exactly the list that needed one, and leaving
 * them separate invites someone to add a `LazyColumn` and forget the bar.
 *
 * The bar is laid over the list rather than given a column of its own, so rows
 * keep the full width right up to the edge and only their trailing padding needs
 * to account for it.
 */
@Composable
fun ScrollableList(
    state: LazyListState,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    // A scrollbar is only drawn when there is something to scroll. Otherwise the
    // thumb stretches to the full height and reads as yet another hard vertical
    // edge -- which is precisely the thing the separators were softened to avoid.
    val scrollable by remember {
        derivedStateOf { state.canScrollBackward || state.canScrollForward }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            content = content,
        )

        if (scrollable) {
            AppVerticalScrollbar(
                state = state,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 3.dp),
            )
        }
    }
}
