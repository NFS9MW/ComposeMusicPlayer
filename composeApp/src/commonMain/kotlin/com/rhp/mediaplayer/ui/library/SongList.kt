package com.rhp.mediaplayer.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.coverart.CoverArtRepository
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.model.SortDirection
import com.rhp.mediaplayer.ui.components.FloatingIconAction
import com.rhp.mediaplayer.ui.components.IconAction
import com.rhp.mediaplayer.ui.components.ScrollableList
import com.rhp.mediaplayer.ui.formatDuration
import com.rhp.mediaplayer.ui.icons.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The main list of songs in the selected playlist.
 *
 * Three details carry most of the weight here:
 *
 *  * **The current-track check is deferred and derived.** A row does not read
 *    `nowPlaying` while composing; it reads it through a lambda inside
 *    `derivedStateOf`, whose value is a plain boolean. Compose then invalidates a
 *    row only when *its own* answer flips, so changing track recomposes the two
 *    rows involved rather than every row on screen.
 *  * **Reordering animations switch themselves off on big lists.** Sorting a few
 *    thousand rows would otherwise start a few thousand concurrent placement
 *    animations, which is a stall, not a flourish.
 *  * **Search narrows what is shown, never what is queued.** Filtering must not
 *    rebuild the queue: that would re-permute a shuffled queue and move the
 *    playhead every time a letter was typed. The list is a view onto the
 *    playlist, and picking a result selects it in the queue as it always did.
 */
@Composable
fun SongList(
    songs: List<Song>,
    totalSongCount: Int,
    searchQuery: String,
    nowPlayingId: () -> String?,
    coverArt: CoverArtRepository,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    /**
     * Reports where the search field sits, so the app can tell a press on it
     * apart from a press anywhere else.
     */
    onSearchFieldBounds: (Rect) -> Unit,
    /** Reported so the window knows when a key press would be typing rather than a shortcut. */
    onSearchFocusChange: (Boolean) -> Unit,
    sortDirection: SortDirection,
    onToggleSort: () -> Unit,
    onRescan: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val searching = searchQuery.isNotBlank()
    val scope = rememberCoroutineScope()

    /** The row a jump has just landed on, for as long as the flash lasts. */
    var flashedId by remember { mutableStateOf<String?>(null) }

    // One timer per jump rather than one per frame: setting the id starts it,
    // and clearing the id is what it does when it runs out.
    LaunchedEffect(flashedId) {
        if (flashedId == null) return@LaunchedEffect
        delay(FLASH_MS)
        flashedId = null
    }

    // Narrowing the list should show the start of the results rather than
    // whatever slice happened to be on screen.
    LaunchedEffect(searchQuery) {
        if (songs.isNotEmpty()) listState.scrollToItem(0)
    }

    /**
     * Where the current track sits, when it is not on screen.
     *
     * The whole condition for the floating button, and the reason it is a
     * *position* rather than a flag: the button is only worth showing when
     * pressing it would move something. A current row already in view makes it a
     * control that visibly does nothing.
     *
     * Null covers three cases that all mean "no button": nothing is current, the
     * current track has been filtered out of the visible list (there is nowhere
     * to scroll to), and the list has not been laid out yet -- without that last
     * check the answer on the very first frame is "not visible", and the button
     * would flash up and straight back down on every launch.
     */
    val offScreenCurrent = remember(listState, songs) {
        derivedStateOf {
            val id = nowPlayingId() ?: return@derivedStateOf null
            val index = songs.indexOfFirst { it.id == id }
            if (index < 0) return@derivedStateOf null

            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf null
            if (visible.any { it.index == index }) null else index
        }
    }

    Column(modifier = modifier) {
        ListHeader(
            visibleCount = songs.size,
            totalCount = totalSongCount,
            searching = searching,
            searchQuery = searchQuery,
            onSearchChange = onSearchChange,
            onSearchFieldBounds = onSearchFieldBounds,
            onSearchFocusChange = onSearchFocusChange,
            sortDirection = sortDirection,
            onToggleSort = onToggleSort,
            onRescan = onRescan,
            onImportFiles = onImportFiles,
            onImportFolder = onImportFolder,
            isBusy = isBusy,
        )

        if (songs.isEmpty()) {
            if (searching) {
                NoSearchResults(
                    query = searchQuery,
                    onClear = { onSearchChange("") },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyLibrary(
                    onImportFiles = onImportFiles,
                    onImportFolder = onImportFolder,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return
        }

        val animateReordering = songs.size <= REORDER_ANIMATION_LIMIT

        Box(modifier = Modifier.fillMaxSize()) {
            ScrollableList(
                state = listState,
                // The extra end padding keeps the durations clear of the scrollbar.
                contentPadding = PaddingValues(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            ) {
                items(
                    items = songs,
                    key = { song -> song.id },
                    // Every row is structurally identical, so the lazy list can
                    // reuse item slots instead of rebuilding them.
                    contentType = { "song" },
                ) { song ->
                    SongRow(
                        song = song,
                        nowPlayingId = nowPlayingId,
                        coverArt = coverArt,
                        onPlay = onPlay,
                        onRemove = onRemove,
                        animateReordering = animateReordering,
                        flashedId = { flashedId },
                    )
                }
            }

            LocateCurrentButton(
                offScreenIndex = offScreenCurrent,
                onLocate = { index ->
                    // The flash is what to look at; the scroll is what gets it
                    // there. Setting the id first means the row is already lit by
                    // the time the movement stops.
                    songs.getOrNull(index)?.let { song ->
                        flashedId = song.id
                        scope.launch { listState.centreOn(index) }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // Clear of the scrollbar, which sits in the trailing gutter.
                    .padding(end = 22.dp, bottom = 18.dp),
            )
        }
    }
}

/**
 * The floating button that takes the list back to the track that is playing.
 *
 * It reads [offScreenIndex] here rather than in [SongList] on purpose. The answer
 * flips every time the list is scrolled past the current row, and a read in the
 * caller would recompose the whole list -- header, item provider and all -- on
 * each of those flips. Down here it recomposes a button.
 */
@Composable
private fun LocateCurrentButton(
    offScreenIndex: State<Int?>,
    onLocate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = offScreenIndex.value

    AnimatedVisibility(
        visible = index != null,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f),
        exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.7f),
    ) {
        FloatingIconAction(
            icon = AppIcons.Locate,
            contentDescription = "定位到正在播放的歌曲",
            tooltip = "定位到正在播放",
            // Non-null whenever this is on screen, and captured so the click does
            // not have to work the position out again.
            onClick = { index?.let(onLocate) },
        )
    }
}

/**
 * Scrolls [index] to the middle of the viewport.
 *
 * The middle rather than the top edge: a row that arrives at the very top of a
 * list looks like the row that was already there, which is the problem the jump
 * exists to solve. Rows are uniform, so any visible one gives the height to aim
 * with -- and if the list has not been laid out yet there is nothing to aim
 * with, so the plain scroll is the honest fallback.
 */
private suspend fun LazyListState.centreOn(index: Int) {
    val info = layoutInfo
    val rowHeight = info.visibleItemsInfo.firstOrNull()?.size ?: 0
    if (rowHeight == 0) {
        animateScrollToItem(index)
        return
    }
    val centred = ((info.viewportSize.height - rowHeight) / 2).coerceAtLeast(0)
    animateScrollToItem(index, -centred)
}

@Composable
private fun ListHeader(
    visibleCount: Int,
    totalCount: Int,
    searching: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSearchFieldBounds: (Rect) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    sortDirection: SortDirection,
    onToggleSort: () -> Unit,
    onRescan: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    isBusy: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "歌曲", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = when {
                    isBusy -> "正在读取…"
                    searching -> "$visibleCount / $totalCount 首"
                    else -> "$totalCount 首"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SearchField(
            query = searchQuery,
            onQueryChange = onSearchChange,
            onBoundsChanged = onSearchFieldBounds,
            onFocusChanged = onSearchFocusChange,
            modifier = Modifier.width(230.dp),
        )

        Spacer(Modifier.width(10.dp))

        val ascending = sortDirection == SortDirection.Ascending

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconAction(
                icon = if (ascending) AppIcons.SortAscending else AppIcons.SortDescending,
                contentDescription = if (ascending) {
                    "当前按名称升序，点击切换为降序"
                } else {
                    "当前按名称降序，点击切换为升序"
                },
                tooltip = if (ascending) "按名称排序，点击改为降序" else "按名称排序，点击改为升序",
                onClick = onToggleSort,
                size = 34,
            )
            IconAction(
                icon = AppIcons.Refresh,
                contentDescription = "重新检查整个歌单，移除文件已不存在的歌曲",
                tooltip = "检查并清除失效歌曲",
                onClick = onRescan,
                enabled = !isBusy,
                size = 34,
            )
            IconAction(
                icon = AppIcons.MusicNote,
                contentDescription = "添加文件",
                tooltip = "添加文件",
                onClick = onImportFiles,
                enabled = !isBusy,
                size = 34,
            )
            IconAction(
                icon = AppIcons.Folder,
                contentDescription = "添加文件夹",
                tooltip = "添加文件夹",
                onClick = onImportFolder,
                enabled = !isBusy,
                size = 34,
            )
        }
    }
}

/**
 * A compact search box.
 *
 * Built on [BasicTextField] rather than the Material text field: the Material
 * one carries a label slot, an indicator line and a 56 dp minimum height, all of
 * which fight a toolbar row. This needs to be a quiet rounded field sitting level
 * with the buttons beside it.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    // The field already tracks this to draw its own focus ring; what is new is
    // telling the rest of the app, which needs it to leave the space bar alone.
    LaunchedEffect(focused) { onFocusChanged(focused) }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        // Reported in root coordinates so the app can hit-test a press against
        // it. Rects compare by value, so re-reporting the same bounds does not
        // invalidate anything.
        modifier = modifier.onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        if (focused) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = if (focused) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            Color.Transparent
                        },
                        shape = shape,
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )

                Spacer(Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索歌名或歌手",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }

                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "清除搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(15.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onQueryChange("") },
                    )
                }
            }
        },
    )
}

@Composable
private fun LazyItemScope.SongRow(
    song: Song,
    nowPlayingId: () -> String?,
    coverArt: CoverArtRepository,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    animateReordering: Boolean,
    flashedId: () -> String?,
) {
    // Deferred read: this composable is invalidated only when its own answer
    // flips, not whenever the current track changes.
    val isCurrent by remember(song.id) {
        derivedStateOf { nowPlayingId() == song.id }
    }

    // Deferred for the same reason, and it matters more here: the flash changes
    // twice per jump, and a read in the list would drag every row into it.
    val flashed by remember(song.id) {
        derivedStateOf { flashedId() == song.id }
    }

    // Reading the map subscribes to this song's key alone.
    val artwork: ImageBitmap? = coverArt.get(song.id)
    if (artwork == null) {
        coverArt.request(song.id, song.path)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val resting = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }

    // Lerped rather than swapped, so hovering still changes colour instantly --
    // the animation belongs to the flash alone. The flash fades in quickly and
    // leaves slowly, which is the shape of a thing being pointed out.
    val flash by animateFloatAsState(
        targetValue = if (flashed) 1f else 0f,
        animationSpec = tween(if (flashed) FLASH_IN_MS else FLASH_OUT_MS),
    )
    val background = if (flash > 0f) {
        lerp(resting, MaterialTheme.colorScheme.primaryContainer, flash)
    } else {
        resting
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (animateReordering) {
                    Modifier.animateItem(
                        tween(ITEM_ANIMATION_MS),
                        tween(ITEM_ANIMATION_MS),
                        tween(ITEM_ANIMATION_MS),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null) { onPlay(song.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(artwork = artwork, isCurrent = isCurrent)

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.album?.let { "${song.displayArtist} · $it" } ?: song.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        if (hovered) {
            IconAction(
                icon = AppIcons.Delete,
                contentDescription = "从歌单移除",
                tooltip = "从歌单移除",
                onClick = { onRemove(song.id) },
                size = 30,
            )
            Spacer(Modifier.width(4.dp))
        }

        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CoverThumbnail(artwork: ImageBitmap?, isCurrent: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
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
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(text = "歌单还是空的", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "添加一些音乐文件，或选择一个文件夹自动扫描",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImportFiles) {
                    Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加文件")
                }
                OutlinedButton(onClick = onImportFolder) {
                    Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加文件夹")
                }
            }
        }
    }
}

@Composable
private fun NoSearchResults(
    query: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = "没有匹配「$query」的歌曲",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "搜索会同时匹配歌名与歌手",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onClear) { Text("清除搜索") }
        }
    }
}

/** Above this many rows, reordering is instant rather than animated. */
private const val REORDER_ANIMATION_LIMIT = 500

private const val ITEM_ANIMATION_MS = 180

/** How long the located row stays lit after a jump. */
private const val FLASH_MS = 1100L

private const val FLASH_IN_MS = 90
private const val FLASH_OUT_MS = 520
