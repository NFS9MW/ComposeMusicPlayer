package com.rhp.mediaplayer.settings

import com.rhp.mediaplayer.model.LoopMode
import com.rhp.mediaplayer.model.Playlist
import com.rhp.mediaplayer.model.SortDirection
import kotlinx.serialization.Serializable

/**
 * Preferences that outlive a session.
 *
 * Everything here is user-visible state that should survive a restart, so the
 * defaults are chosen to be reasonable on a first run rather than to match any
 * particular internal state: dark theme on, full volume, ascending names.
 */
@Serializable
data class PlayerSettings(
    val darkTheme: Boolean = true,
    val volume: Float = 1f,
    val sortDirection: SortDirection = SortDirection.Ascending,
    val shuffle: Boolean = false,
    val loop: LoopMode = LoopMode.Off,
    /** Where the file chooser should open next time. */
    val lastImportDirectory: String? = null,
    val queuePanelExpanded: Boolean = true,
    /**
     * Whether the window was maximised when the app last closed.
     *
     * Only the maximised flag is remembered, not the size and position: a
     * restored position is worthless the moment the window moves to a different
     * monitor arrangement, and a stale one can strand the window off-screen.
     */
    val windowMaximized: Boolean = false,
    /**
     * What was on the playhead when the app last closed, and how far into it.
     *
     * The queue itself is rebuilt from the playlist on the next launch, so these
     * are the only two facts that cannot be derived from what is already stored.
     *
     * Nothing is resumed automatically: reopening a window should not start
     * making noise. The track is left cued at that point instead, so pressing
     * play carries on rather than starting over.
     */
    val lastSongId: String? = null,
    val lastPositionMs: Long = 0L,
) {
    fun normalized(): PlayerSettings = copy(volume = volume.coerceIn(0f, 1f))
}

/**
 * The playlists, and which one the user was looking at.
 *
 * Nothing about the queue is stored. A shuffled order is deliberately not kept:
 * the queue is rebuilt from the playlist on every launch, so a shuffled one is
 * dealt fresh and the whole playlist is available again rather than the tail of
 * a pass that started last time. The sequential playhead is the one piece of
 * playback state that is remembered, and it lives in [PlayerSettings] with the
 * rest of the preferences.
 */
@Serializable
data class PersistedLibrary(
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: String? = null,
)

/**
 * Where the app keeps its state.
 *
 * The interface exists so that the controller can be exercised without touching
 * the real user profile directory, and so a future change of format stays on
 * one side of the boundary.
 */
interface AppStorage {
    fun loadLibrary(): PersistedLibrary
    fun saveLibrary(library: PersistedLibrary)

    fun loadSettings(): PlayerSettings
    fun saveSettings(settings: PlayerSettings)
}
