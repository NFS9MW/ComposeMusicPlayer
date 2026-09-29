package com.rhp.mediaplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.coverart.CoverArtRepository
import com.rhp.mediaplayer.player.PlayerController
import com.rhp.mediaplayer.ui.library.SongList
import com.rhp.mediaplayer.ui.nowplaying.NowPlayingScreen
import com.rhp.mediaplayer.ui.playlist.PlaylistSidebar
import com.rhp.mediaplayer.ui.queue.QueuePanel
import com.rhp.mediaplayer.ui.theme.AppTheme
import com.rhp.mediaplayer.ui.transport.TransportBar

private val SIDEBAR_WIDTH = 236.dp
private val QUEUE_WIDTH = 312.dp

/**
 * The whole window: playlists on the left, the playlist in the middle, the real
 * play order on the right, transport along the bottom.
 *
 * Every callback here forwards straight to the controller. There is no local
 * state beyond what the panels own, so what is on screen is always exactly what
 * the controller and the queue believe.
 */
@Composable
fun App(
    controller: PlayerController,
    coverArt: CoverArtRepository,
) {
    val focusManager = LocalFocusManager.current
    var searchFieldBounds by remember { mutableStateOf<Rect?>(null) }

    // A now-playing screen with nothing to show is just an empty window. That
    // happens when the playlist it was covering is emptied or switched while it
    // is open, so the screen stands itself down rather than lingering blank.
    val hasTrack = controller.nowPlaying != null
    LaunchedEffect(hasTrack) {
        if (!hasTrack) controller.closeNowPlayingScreen()
    }

    // Pressing anywhere other than the search field drops its focus, so the
    // caret stops following the user around once they have moved on.
    //
    // Compose does not do this by itself, and the obvious shortcut -- clear
    // focus whenever a press went unconsumed -- does not work here. A lazy
    // list's scroll handler swallows presses across its whole area, including
    // the empty space below the last row, which is exactly where people click
    // to dismiss. Comparing the press position against the field's bounds has no
    // such blind spot, and it also covers pressing a song row.
    val dismissSearchFocus = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val position = event.changes.firstOrNull()?.position ?: continue
                val bounds = searchFieldBounds
                if (bounds == null || !bounds.contains(position)) {
                    focusManager.clearFocus()
                }
            }
        }
    }

    AppTheme(darkTheme = controller.darkTheme) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize().then(dismissSearchFocus)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // The library and the now-playing screen take turns in the
                    // space above the transport bar. Sharing one slot rather than
                    // layering them keeps the bar -- and therefore the way back --
                    // in the same place whichever of the two is showing.
                    //
                    // The queue panel is deliberately a sibling of that slot
                    // rather than a third thing inside it. The list button in the
                    // transport bar is how the queue is summoned, so it has to
                    // work from either view -- and a panel drawn inside the slot
                    // would be covered by the very screen the button was pressed
                    // over. Out here it slides in beside whatever is showing, and
                    // nothing ends up hidden behind anything.
                    Box(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // The sidebar and the list, which the now-playing
                            // screen is allowed to cover.
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    PlaylistSidebar(
                                        playlists = controller.playlists,
                                        selectedId = controller.selectedPlaylistId,
                                        onSelect = controller::selectPlaylist,
                                        onCreate = controller::createPlaylist,
                                        onRename = controller::renamePlaylist,
                                        onDelete = controller::deletePlaylist,
                                        darkTheme = controller.darkTheme,
                                        onToggleTheme = controller::toggleTheme,
                                        queuePanelExpanded = controller.queuePanelExpanded,
                                        onToggleQueue = controller::toggleQueuePanel,
                                        modifier = Modifier
                                            .width(SIDEBAR_WIDTH)
                                            .fillMaxSize()
                                            // A recessed panel tone in place of a divider line.
                                            .background(MaterialTheme.colorScheme.surfaceContainer),
                                    )

                                    SongList(
                                        songs = controller.visibleSongs,
                                        totalSongCount = controller.displaySongs.size,
                                        searchQuery = controller.searchQuery,
                                        // Deferred on purpose: the list must not subscribe to
                                        // the current track, or every row would be
                                        // invalidated on every track change.
                                        nowPlayingId = { controller.nowPlaying?.id },
                                        coverArt = coverArt,
                                        onPlay = controller::playSong,
                                        onRemove = controller::removeSong,
                                        onSearchChange = controller::updateSearchQuery,
                                        // The search field reports where it is, so a press
                                        // anywhere else can be told apart from a press on it.
                                        onSearchFieldBounds = { searchFieldBounds = it },
                                        onSearchFocusChange = controller::updateSearchFieldFocused,
                                        sortDirection = controller.sortDirection,
                                        onToggleSort = controller::toggleSortDirection,
                                        onRescan = controller::rescanLibrary,
                                        onImportFiles = controller::importFiles,
                                        onImportFolder = controller::importFolder,
                                        isBusy = controller.isBusy,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                    )
                                }

                                // Last child of this box, so it covers the
                                // sidebar and the list -- and stops at the
                                // queue panel, which is a column of its own.
                                NowPlayingSlot(controller, coverArt)
                            }

                            AnimatedVisibility(
                                visible = controller.queuePanelExpanded,
                                enter = fadeIn(tween(180)) + expandHorizontally(tween(220)),
                                exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .width(QUEUE_WIDTH)
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    QueuePanel(
                                        nowPlaying = controller.nowPlaying,
                                        playedInPass = controller.playedInPass,
                                        upNext = controller.upNext,
                                        shuffle = controller.shuffleEnabled,
                                        loop = controller.loopMode,
                                        playbackStarted = controller.playbackStarted,
                                        coverArt = coverArt,
                                        onSelect = controller::playSong,
                                        onCollapse = controller::toggleQueuePanel,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }

                    TransportBar(
                        nowPlaying = controller.nowPlaying,
                        isPlaying = controller.isPlaying,
                        playbackStarted = controller.playbackStarted,
                        largeArt = coverArt.largeArt,
                        // Providers, not values: only the sliders that read them
                        // recompose when playback advances or volume changes.
                        positionMsProvider = { controller.positionMs },
                        volumeProvider = { controller.volume },
                        shuffle = controller.shuffleEnabled,
                        loop = controller.loopMode,
                        queuePanelExpanded = controller.queuePanelExpanded,
                        onTogglePlay = controller::togglePlayPause,
                        onNext = controller::next,
                        onPrevious = controller::previous,
                        onSeek = controller::seekTo,
                        onVolume = controller::changeVolume,
                        onToggleShuffle = controller::toggleShuffle,
                        onCycleLoop = controller::cycleLoopMode,
                        onToggleQueue = controller::toggleQueuePanel,
                        onToggleNowPlaying = controller::toggleNowPlayingScreen,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Raised one step above the panels, since this bar is
                            // always present and controls everything else.
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }

                controller.statusMessage?.let { message ->
                    StatusPill(
                        text = message,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = SIDEBAR_WIDTH + 20.dp, bottom = 116.dp),
                    )
                }
            }
        }
    }

    controller.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = controller::dismissError,
            title = { Text("播放失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = controller::dismissError) { Text("知道了") }
            },
        )
    }
}

/**
 * The now-playing screen, shown over the library.
 *
 * Its own composable rather than a block inline in the layout below, because
 * `AnimatedVisibility` also has a `ColumnScope` overload and inside the column
 * that arranges the window the wrong one is picked up.
 */
@Composable
private fun NowPlayingSlot(
    controller: PlayerController,
    coverArt: CoverArtRepository,
) {
    AnimatedVisibility(
        modifier = Modifier.fillMaxSize(),
        visible = controller.nowPlayingScreenOpen,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(140)),
    ) {
        NowPlayingScreen(
            song = controller.nowPlaying,
            artwork = coverArt.largeArt,
            // Nothing else is passed: the screen shows the sleeve and the
            // names, and every control stays on the bar below it.
            onClose = controller::closeNowPlayingScreen,
        )
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
