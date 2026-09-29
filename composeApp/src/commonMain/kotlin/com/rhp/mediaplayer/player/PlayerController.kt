package com.rhp.mediaplayer.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rhp.mediaplayer.coverart.CoverArtRepository
import com.rhp.mediaplayer.library.LibraryService
import com.rhp.mediaplayer.model.LoopMode
import com.rhp.mediaplayer.model.Playlist
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.model.SortDirection
import com.rhp.mediaplayer.model.TextCollator
import com.rhp.mediaplayer.model.filterBySearchQuery
import com.rhp.mediaplayer.model.sortedByName
import com.rhp.mediaplayer.settings.AppStorage
import com.rhp.mediaplayer.settings.PersistedLibrary
import com.rhp.mediaplayer.settings.PlayerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The single place the UI talks to.
 *
 * It owns the engine, the queue and the persisted library, and it is the only
 * thing that writes Compose state. Everything the screens need is exposed as an
 * observable property; everything they want to do is a method.
 *
 * Preferences are exposed one property at a time rather than as a single
 * [PlayerSettings] object. That is deliberate: a Compose state holding the whole
 * object would invalidate every reader on any change, so dragging the volume
 * slider would recompose the entire screen. Split up, a volume drag touches
 * only the transport bar.
 *
 * Two more rules shape the code:
 *
 *  * **The engine reports from its own thread.** Completion and error callbacks
 *    arrive on the audio pump, so they are hopped onto [scope], which runs on
 *    the UI dispatcher.
 *  * **Expensive state changes are batched.** Tag reading streams in one song
 *    at a time; re-sorting and re-permuting the queue per song would be
 *    O(n log n) work multiplied by the size of the library.
 */
class PlayerController(
    private val engine: AudioEngine,
    private val library: LibraryService,
    private val storage: AppStorage,
    private val collator: TextCollator,
    private val coverArt: CoverArtRepository,
    private val scope: CoroutineScope,
    private val queue: PlaybackQueue = PlaybackQueue(),
) {

    // ------------------------------------------------------- library state

    var playlists: List<Playlist> by mutableStateOf(emptyList())
        private set

    var selectedPlaylistId: String? by mutableStateOf(null)
        private set

    /** The selected playlist's songs in the currently chosen sort order. */
    var displaySongs: List<Song> by mutableStateOf(emptyList())
        private set

    /** What the search box currently contains. */
    var searchQuery: String by mutableStateOf("")
        private set

    /**
     * What the list actually renders: [displaySongs] narrowed by the search box.
     *
     * Kept separate from the queue on purpose. Filtering the queue instead would
     * re-permute a shuffled one and move the playhead on every keystroke, and it
     * would mean searching could change what plays next. Search is a view onto
     * the playlist; the queue is untouched by it.
     */
    var visibleSongs: List<Song> by mutableStateOf(emptyList())
        private set

    // ------------------------------------------------------ playback state

    var nowPlaying: Song? by mutableStateOf(null)
        private set

    var playedInPass: List<Song> by mutableStateOf(emptyList())
        private set

    var upNext: List<Song> by mutableStateOf(emptyList())
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /**
     * True once the user has actually started something.
     *
     * Loading a playlist puts a track under the cursor immediately, but nothing
     * is playing and the user has not chosen anything. Without this flag the
     * transport bar would claim a song is current while the queue panel
     * correctly said nothing had started -- the two panels have to agree.
     */
    var playbackStarted: Boolean by mutableStateOf(false)
        private set

    /**
     * Read only by the transport bar.
     *
     * Kept out of every list row's read path on purpose: at twelve updates a
     * second, anything else that read it would recompose twelve times a second
     * for no reason.
     */
    var positionMs: Long by mutableStateOf(0L)
        private set

    var isBusy: Boolean by mutableStateOf(false)
        private set

    /** Transient feedback such as "已导入 42 首". Clears itself. */
    var statusMessage: String? by mutableStateOf(null)
        private set

    /** Sticky failure text, dismissed by the user. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    /** True when the last pass ran out and looping is off. */
    var passFinished: Boolean by mutableStateOf(false)
        private set

    // ------------------------------------------------------- preferences

    var darkTheme: Boolean by mutableStateOf(true)
        private set

    var volume: Float by mutableStateOf(1f)
        private set

    var queuePanelExpanded: Boolean by mutableStateOf(true)
        private set

    /** Whether the window should open maximised next time. */
    var windowMaximized: Boolean by mutableStateOf(false)
        private set

    var sortDirection: SortDirection by mutableStateOf(SortDirection.Ascending)
        private set

    var shuffleEnabled: Boolean by mutableStateOf(false)
        private set

    var loopMode: LoopMode by mutableStateOf(LoopMode.Off)
        private set

    /** The persisted form. Kept off the composition; the properties above are its view. */
    private var settings: PlayerSettings = PlayerSettings()

    /**
     * Where playback has got to, for the next launch.
     *
     * Deliberately not Compose state and deliberately not part of [settings]:
     * [positionMs] moves ten times a second, and routing that through either
     * would mean a recomposition of everything that reads a setting, ten times a
     * second, for a value nothing on screen is showing.
     */
    private var resumeSongId: String? = null
    private var resumePositionMs: Long = 0L

    /**
     * True once the playhead has moved, meaning the stored resume point is out
     * of date and the next write has to include the new one.
     *
     * Nothing is written while a track plays -- an album would be a write every
     * few seconds for a number that only has to be roughly right -- so this is
     * what tells [shutdown] that a write is owed even when no setting changed.
     */
    private var resumeMoved: Boolean = false

    /**
     * Whether the search box currently has the keyboard.
     *
     * The one piece of focus state the window needs, and it is here rather than
     * down in the list because the window's key handling is what reads it: a
     * space typed into the search box has to stay a space, and the only thing
     * that can tell the difference is knowing where the typing would go.
     */
    var searchFieldFocused: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the now-playing screen is covering the library.
     *
     * Transient by design, and not part of [settings] either: reopening the app
     * onto a full-window sleeve would hide the library the user came back to
     * browse, so it always starts closed.
     */
    var nowPlayingScreenOpen: Boolean by mutableStateOf(false)
        private set

    val selectedPlaylist: Playlist? get() = playlists.firstOrNull { it.id == selectedPlaylistId }

    /** Which song the engine currently holds, to tell pause from restart. */
    private var loadedSongId: String? = null

    private var saveJob: Job? = null
    private var statusJob: Job? = null

    init {
        engine.listener = object : AudioEngineListener {
            override fun onTrackCompleted() {
                scope.launch { applyOutcome(queue.onTrackFinished()) }
            }

            override fun onPlaybackError(message: String) {
                scope.launch {
                    stopPlayback()
                    errorMessage = message
                }
            }
        }

        restore()
        startPositionTicker()
        engine.prewarm()
    }

    // ------------------------------------------------------------- playback

    fun togglePlayPause() {
        if (engine.isPlaying) {
            engine.pause()
            isPlaying = false
            positionMs = engine.positionMs
            return
        }

        val song = nowPlaying
        if (song == null || passFinished) {
            // Nothing has played yet, or the pass ran out: start at the top.
            applyOutcome(queue.beginNewPass())
            return
        }
        if (loadedSongId == song.id) {
            engine.resume()
            isPlaying = true
        } else {
            // A track that was cued by the last session rather than chosen in
            // this one picks up where it left off. A track chosen from the list
            // starts at the beginning, because selecting it goes through
            // startSong with no offset.
            startSong(song, fromMs = positionMs)
        }
    }

    /** Plays the row the user clicked. */
    fun playSong(songId: String) {
        applyOutcome(queue.select(songId))
    }

    fun next() = applyOutcome(queue.skipNext())

    fun previous() = applyOutcome(queue.skipPrevious())

    fun seekTo(positionMs: Long) {
        val song = nowPlaying ?: return
        val upperBound = song.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, upperBound)
        engine.seekTo(clamped)
        this.positionMs = clamped
    }

    fun seekBy(deltaMs: Long) {
        seekTo(positionMs + deltaMs)
    }

    fun changeVolume(newVolume: Float) {
        val clamped = newVolume.coerceIn(0f, 1f)
        engine.setVolume(clamped)
        updateSettings { it.copy(volume = clamped) }
    }

    fun toggleShuffle() {
        val enabled = !shuffleEnabled
        queue.setShuffle(enabled)
        updateSettings { it.copy(shuffle = enabled) }
        // No bookkeeping here: `syncQueueState` below records the track, and
        // whether the position travels with it is decided in one place, by
        // [noteResumePoint].
        syncQueueState()
    }

    fun cycleLoopMode() {
        val next = loopMode.next()
        queue.setLoop(next)
        // Looping again can revive a queue that had already run out.
        passFinished = false
        updateSettings { it.copy(loop = next) }
    }

    fun toggleSortDirection() {
        // Publishing first matters: the rebuild below reads the new direction.
        updateSettings { it.copy(sortDirection = it.sortDirection.flipped()) }
        rebuildDisplaySongs()
    }

    fun toggleTheme() = updateSettings { it.copy(darkTheme = !it.darkTheme) }

    fun toggleQueuePanel() =
        updateSettings { it.copy(queuePanelExpanded = !it.queuePanelExpanded) }

    /**
     * Raises the now-playing screen, or lowers it again.
     *
     * It refuses to open with nothing to show -- a screen with no track on it is
     * just an empty window -- but it always closes, because closing is the way
     * out and a screen that will not shut is worse than one that will not open.
     */
    fun toggleNowPlayingScreen() {
        if (nowPlayingScreenOpen) {
            nowPlayingScreenOpen = false
        } else if (nowPlaying != null) {
            nowPlayingScreenOpen = true
        }
    }

    fun closeNowPlayingScreen() {
        nowPlayingScreenOpen = false
    }

    /**
     * Records where the window ended up.
     *
     * Driven by the window rather than by a click, so it is a plain setter
     * rather than a toggle. The no-op guard matters more than it looks: the
     * window reports its placement on every state transition, and each report
     * that got through would schedule a write.
     */
    fun updateWindowMaximized(maximized: Boolean) {
        if (maximized == windowMaximized) return
        updateSettings { it.copy(windowMaximized = maximized) }
    }

    fun dismissError() {
        errorMessage = null
    }

    /** Narrows the visible list. Deliberately does not touch the queue. */
    fun updateSearchQuery(query: String) {
        if (query == searchQuery) return
        searchQuery = query
        applySearch()
    }

    /** Reported by the search box so the window knows when not to steal a space. */
    fun updateSearchFieldFocused(focused: Boolean) {
        if (focused == searchFieldFocused) return
        searchFieldFocused = focused
    }

    /**
     * Recomputes [visibleSongs].
     *
     * Called on the three occasions the list can change -- a new search term, a
     * different playlist or sort order, tags arriving -- rather than being a
     * derived state. It is O(n) in the size of the playlist, which is fine at
     * those moments but would not be if it ran on every frame.
     */
    private fun applySearch() {
        visibleSongs = filterBySearchQuery(displaySongs, searchQuery)
    }

    // ----------------------------------------------------------- playlists

    fun selectPlaylist(playlistId: String) {
        if (playlistId == selectedPlaylistId) return
        // Changing playlists is a change of context, so playback stops rather
        // than silently continuing with tracks the user is no longer looking at.
        stopPlayback()
        selectedPlaylistId = playlistId
        rebuildDisplaySongs()
        scheduleSave()
    }

    fun createPlaylist(name: String) {
        val trimmed = name.trim().ifEmpty { "新建歌单" }
        val playlist = Playlist(id = newPlaylistId(), name = trimmed)
        playlists = playlists + playlist
        selectedPlaylistId = playlist.id
        stopPlayback()
        rebuildDisplaySongs()
        scheduleSave()
    }

    fun renamePlaylist(playlistId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        playlists = playlists.map { if (it.id == playlistId) it.copy(name = trimmed) else it }
        scheduleSave()
    }

    fun deletePlaylist(playlistId: String) {
        val wasSelected = selectedPlaylistId == playlistId
        playlists = playlists.filterNot { it.id == playlistId }
        if (wasSelected) {
            stopPlayback()
            selectedPlaylistId = playlists.firstOrNull()?.id
            rebuildDisplaySongs()
        }
        scheduleSave()
    }

    fun removeSong(songId: String) {
        val playlist = selectedPlaylist ?: return
        val wasPlaying = nowPlaying?.id == songId

        playlists = playlists.map { if (it.id == playlist.id) it.removeSong(songId) else it }
        coverArt.clear()

        if (wasPlaying) {
            // Stop before the queue relocates, so the engine does not keep
            // decoding a file the user just removed.
            stopPlayback()
        }
        rebuildDisplaySongs()
        scheduleSave()
    }

    /**
     * Re-checks the selected playlist and drops the entries whose file is gone.
     *
     * The **whole playlist** is checked, not just what the search box is
     * currently showing. A half-checked library is worse than none: the stale
     * entries are exactly the ones a filter hides, so checking only the visible
     * rows would let the button report that all was well while the dead entries
     * sat just out of sight.
     *
     * Nothing is re-read beyond whether the file is there. Titles and cover art
     * are not touched, so this stays a quick check rather than a second import.
     */
    fun rescanLibrary() {
        if (isBusy) return

        val playlist = selectedPlaylist
        if (playlist == null || playlist.songs.isEmpty()) {
            showStatus("歌单还是空的，没有可检查的文件")
            return
        }

        // Captured before suspending: the user is free to switch playlists while
        // the check is running, and the result belongs to the one it started on.
        val playlistId = playlist.id
        val candidates = playlist.songs

        scope.launch {
            isBusy = true
            try {
                val missing = library.findMissingFiles(candidates)
                if (missing.isEmpty()) {
                    showStatus("已检查 ${candidates.size} 首，文件都在")
                    return@launch
                }

                val missingIds = missing.mapTo(HashSet()) { it.id }
                if (nowPlaying?.id in missingIds) {
                    // Stop before the queue relocates, so the engine is not left
                    // decoding a file that has just been removed from under it.
                    stopPlayback()
                }

                playlists = playlists.map { current ->
                    if (current.id == playlistId) current.removeSongs(missingIds) else current
                }
                // Drops the artwork of whatever was just removed, including the
                // large image if it was the track that was playing.
                coverArt.clear()
                if (selectedPlaylistId == playlistId) {
                    rebuildDisplaySongs()
                }
                scheduleSave()

                showStatus("已检查 ${candidates.size} 首，移除 ${missing.size} 首（文件已不存在）")
            } finally {
                isBusy = false
            }
        }
    }

    // ------------------------------------------------------------- import

    /** Opens a multi-select file dialog and imports whatever is chosen. */
    fun importFiles() = launchImport {
        library.chooseAudioFiles(settings.lastImportDirectory)
    }

    /** Opens a folder dialog and scans it recursively. */
    fun importFolder() = launchImport {
        listOfNotNull(library.chooseDirectory(settings.lastImportDirectory))
    }

    private fun launchImport(pick: () -> List<String>) {
        if (isBusy) return
        scope.launch {
            // The dialog blocks the event thread while it is open, which is how
            // modal dialogs behave everywhere; the work after it does not.
            val chosen = pick()
            if (chosen.isEmpty()) return@launch

            isBusy = true
            try {
                val target = ensurePlaylist()
                val stubs = library.discover(chosen)
                if (stubs.isEmpty()) {
                    showStatus("没有找到可导入的音频文件")
                } else {
                    importStubs(target, stubs, chosen)
                }
            } finally {
                isBusy = false
            }
        }
    }

    private suspend fun importStubs(target: Playlist, stubs: List<Song>, chosen: List<String>) {
        val alreadyPresent = target.songs.mapTo(HashSet()) { it.id }

        playlists = playlists.map { if (it.id == target.id) it.addSongs(stubs) else it }
        library.parentDirectory(chosen.first())?.let { directory ->
            updateSettings { it.copy(lastImportDirectory = directory) }
        }
        rebuildDisplaySongs()

        val added = stubs.count { it.id !in alreadyPresent }
        if (added == 0) {
            showStatus("这些文件已经在歌单里了")
            scheduleSave()
            return
        }

        showStatus("已导入 $added 首，正在读取标签…")
        readMetadataInBatches(target.id, stubs)
        showStatus("已导入 $added 首")
        scheduleSave()
    }

    /**
     * Reads tags for [stubs] and applies them in batches.
     *
     * Flushing on a size or time threshold rather than per song is what keeps a
     * thousand-file import from re-sorting the list a thousand times.
     */
    private suspend fun readMetadataInBatches(playlistId: String, stubs: List<Song>) {
        val pending = LinkedHashMap<String, Song>()
        val started = TimeSource.Monotonic.markNow()
        var lastFlushAt = 0L

        library.metadata(stubs).collect { enriched ->
            pending[enriched.id] = enriched
            val elapsed = started.elapsedNow().inWholeMilliseconds
            if (pending.size >= METADATA_BATCH_SIZE || elapsed - lastFlushAt >= METADATA_FLUSH_MS) {
                applyMetadata(playlistId, pending.values.toList())
                pending.clear()
                lastFlushAt = elapsed
            }
        }
        if (pending.isNotEmpty()) {
            applyMetadata(playlistId, pending.values.toList())
        }
    }

    private fun applyMetadata(playlistId: String, updated: List<Song>) {
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.id }

        playlists = playlists.map { playlist ->
            if (playlist.id != playlistId) {
                playlist
            } else {
                playlist.copy(songs = playlist.songs.map { byId[it.id] ?: it })
            }
        }
        // Metadata must not reorder or re-permute anything.
        queue.refreshMetadata(updated)
        displaySongs = displaySongs.map { byId[it.id] ?: it }
        // A title can be the very thing being searched for, so re-filter.
        applySearch()
        syncQueueState()
    }

    private fun ensurePlaylist(): Playlist {
        selectedPlaylist?.let { return it }
        val playlist = Playlist(id = newPlaylistId(), name = "我的音乐")
        playlists = playlists + playlist
        selectedPlaylistId = playlist.id
        return playlist
    }

    // ------------------------------------------------------------ internals

    private fun startSong(song: Song, fromMs: Long = 0L) {
        loadedSongId = song.id
        positionMs = fromMs
        playbackStarted = true
        engine.setVolume(volume)
        engine.play(song, fromMs)
        isPlaying = true
        coverArt.requestLarge(song.id, song.path)
    }

    private fun applyOutcome(outcome: QueueOutcome) {
        when (outcome) {
            is QueueOutcome.Play -> startSong(outcome.song)

            QueueOutcome.Stop -> {
                // The pass ran out with looping off: park on the last track.
                engine.stop()
                loadedSongId = null
                isPlaying = false
                passFinished = true
            }

            QueueOutcome.Empty -> stopPlayback()
        }
        syncQueueState()
    }

    private fun stopPlayback() {
        engine.stop()
        loadedSongId = null
        isPlaying = false
        playbackStarted = false
        positionMs = 0
        passFinished = false
        syncQueueState()
    }

    /** Mirrors the queue's decisions into observable state. */
    private fun syncQueueState() {
        nowPlaying = queue.current
        playedInPass = queue.playedInPass
        upNext = queue.upNext
        // The queue is the authority on what is current, so a track change, a
        // playlist switch or a removal all have to move the resume point too --
        // otherwise closing after one of those would bring back a track the app
        // is no longer sitting on.
        noteResumePoint()
    }

    /**
     * Remembers the playhead for the next launch.
     *
     * Cheap enough to call on every tick: it writes two fields, and only tells
     * [shutdown] that a write is owed. The disk is not touched until then.
     *
     * **Which** track is remembered in both modes; *how far into it* only when
     * playing in order. That split is the whole design, and it is what the two
     * modes actually want:
     *
     *  * in order, the position means something -- six tracks into the list is
     *    six tracks into the listening -- so it comes back with the track;
     *  * shuffling, it does not. The permutation is dealt fresh on every launch,
     *    so a position picked out of the old one would land in the middle of an
     *    unrelated order with an arbitrary set of songs reported as already
     *    heard. Carrying over the *track* but not the point inside it is what
     *    makes reopening continue with what the user was listening to without
     *    pretending to know how far in they were.
     */
    private fun noteResumePoint() {
        val songId = nowPlaying?.id
        val position = if (shuffleEnabled) 0L else positionMs.coerceAtLeast(0L)
        if (songId == resumeSongId && position == resumePositionMs) return
        resumeSongId = songId
        resumePositionMs = position
        resumeMoved = true
    }

    /**
     * Re-sorts the selected playlist and hands the result to the queue.
     *
     * Called for structural changes only -- import, removal, re-sort, playlist
     * switch. Metadata updates take the cheaper [applyMetadata] path.
     */
    private fun rebuildDisplaySongs() {
        val playlist = selectedPlaylist
        if (playlist == null) {
            displaySongs = emptyList()
            applySearch()
            stopPlayback()
            queue.setSongs(emptyList())
            syncQueueState()
            return
        }

        val sorted = sortedByName(playlist.songs, collator, sortDirection)
        displaySongs = sorted
        applySearch()
        queue.setSongs(sorted)
        syncQueueState()
    }

    private fun restore() {
        val persisted = storage.loadLibrary()
        settings = storage.loadSettings()
        publishSettings(settings)

        // Older libraries can carry a title or an artist that is really the
        // file's own path; see Song.withoutLeakedPathMetadata. Repaired here
        // rather than at import, because nothing else revisits a stored title --
        // a rescan checks that files still exist and deliberately leaves names
        // alone -- so such an entry would list its directory for ever.
        playlists = persisted.playlists.map { it.withoutLeakedPathMetadata() }
        selectedPlaylistId = persisted.selectedPlaylistId?.takeIf { id ->
            persisted.playlists.any { it.id == id }
        } ?: persisted.playlists.firstOrNull()?.id

        engine.setVolume(settings.volume)
        queue.setShuffle(settings.shuffle)
        queue.setLoop(settings.loop)
        rebuildDisplaySongs()
        restoreResumePoint()

        // Positions are rebuilt, but nothing is playing yet.
        loadedSongId = null
        isPlaying = false

        // Seeded rather than recorded: the state now matches what was loaded, so
        // the playhead does not owe the disk a write yet.
        resumeSongId = nowPlaying?.id
        resumePositionMs = positionMs
        resumeMoved = false
    }

    /**
     * Puts the playhead back on the track the last session ended on.
     *
     * Only the cue: nothing starts playing by itself. Which track, and how much
     * of it, is entirely [noteResumePoint]'s business -- all this does is apply
     * what was stored. In both modes the track comes back; the position only
     * comes back when it was stored, which is to say when playing in order.
     */
    private fun restoreResumePoint() {
        val song = settings.lastSongId
            ?.let { id -> displaySongs.firstOrNull { it.id == id } }
            ?: return

        queue.resumeAt(song.id)
        // In order, a position in the list stands for the listening, so the
        // tracks ahead of it are counted as heard. Nothing is persisted to say
        // so; this is the inference that makes reopening in the middle of an
        // album land where it should. Meaningless while shuffling, where the
        // permutation was dealt fresh a moment ago.
        if (!shuffleEnabled) {
            queue.assumePlayedUpTo(song.id)
        }
        syncQueueState()
        positionMs = resumePositionFor(song)
    }

    /**
     * How far into [song] playback should pick up.
     *
     * A position in the last few seconds is dropped rather than honoured: that
     * is not a place anyone left a track deliberately, and resuming there means
     * the track ends moments after it starts. The same goes for a position past
     * the end, which is what a re-encoded or replaced file looks like.
     */
    private fun resumePositionFor(song: Song): Long {
        val saved = settings.lastPositionMs
        if (saved <= 0L) return 0L
        val duration = song.durationMs?.takeIf { it > 0L } ?: return saved
        if (saved >= duration - RESUME_TAIL_MARGIN_MS) return 0L
        return saved
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                if (engine.isPlaying) {
                    positionMs = engine.positionMs
                    // The one place the playhead is watched continuously, so the
                    // one place the resume point can be kept up to date.
                    noteResumePoint()
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun updateSettings(transform: (PlayerSettings) -> PlayerSettings) {
        settings = transform(settings).normalized()
        publishSettings(settings)
        scheduleSave()
    }

    private fun publishSettings(value: PlayerSettings) {
        darkTheme = value.darkTheme
        volume = value.volume
        queuePanelExpanded = value.queuePanelExpanded
        windowMaximized = value.windowMaximized
        sortDirection = value.sortDirection
        shuffleEnabled = value.shuffle
        loopMode = value.loop
    }

    private fun showStatus(message: String?) {
        statusJob?.cancel()
        statusMessage = message
        if (message != null) {
            statusJob = scope.launch {
                delay(STATUS_LIFETIME_MS)
                statusMessage = null
            }
        }
    }

    /**
     * Writes settings and playlists after a short quiet period.
     *
     * Dragging the volume slider would otherwise hit the disk on every frame.
     */
    private fun scheduleSave() {
        val settingsSnapshot = currentSettings()
        val librarySnapshot = currentLibrary()
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(SAVE_DEBOUNCE_MS)
            writeState(settingsSnapshot, librarySnapshot)
        }
    }

    /**
     * The settings as they should be written.
     *
     * The resume point is layered over the stored copy here rather than being
     * kept in [settings] itself, because [settings] is rewritten through
     * `updateSettings` by every preference, and those rewrites must not drag a
     * stale playhead back onto disk.
     */
    private fun currentSettings(): PlayerSettings =
        settings.copy(lastSongId = resumeSongId, lastPositionMs = resumePositionMs)

    /** The library as it should be written, for the same reason as [currentSettings]. */
    private fun currentLibrary(): PersistedLibrary =
        PersistedLibrary(playlists, selectedPlaylistId)

    private fun writeState(settings: PlayerSettings, library: PersistedLibrary) {
        storage.saveSettings(settings)
        storage.saveLibrary(library)
    }

    /** Stops audio and frees the decoder. The controller is done after this. */
    fun shutdown() {
        statusJob?.cancel()

        // Flush the debounced write instead of cancelling it away. The debounce
        // exists to avoid a write per frame, but the last change before quitting
        // is the one the user just made -- and for the window placement it is
        // the only change there is, because maximising and then closing is the
        // ordinary way to leave.
        //
        // Only when a write is actually owed: a controller that has not changed
        // anything already matches what is on disk, and rewriting the whole
        // library on every exit would be pure waste. `resumeMoved` is the other
        // reason a write is owed, and it is the common one -- playing an album
        // changes no setting at all, so the playhead would never reach the disk.
        val savePending = saveJob?.isActive == true
        saveJob?.cancel()
        if (savePending || resumeMoved) {
            writeState(currentSettings(), currentLibrary())
        }

        engine.release()
    }

    private fun newPlaylistId(): String = "pl-" + Random.nextLong().toULong().toString(16)

    private companion object {
        const val POSITION_TICK_MS = 80L
        const val STATUS_LIFETIME_MS = 4000L
        const val SAVE_DEBOUNCE_MS = 500L
        const val METADATA_BATCH_SIZE = 40
        const val METADATA_FLUSH_MS = 300L

        /**
         * How close to the end of a track counts as "finished" when deciding
         * whether the stored playhead is worth resuming from.
         */
        const val RESUME_TAIL_MARGIN_MS = 5_000L
    }
}
