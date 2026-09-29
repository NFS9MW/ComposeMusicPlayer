package com.rhp.mediaplayer.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A named, persisted collection of songs.
 *
 * Playlists own their songs outright rather than referencing a shared library
 * table. The duplicate metadata across playlists costs a few hundred bytes each
 * and buys a much simpler model: deleting a playlist cannot leave dangling
 * references, and importing the same folder twice is handled by de-duplicating
 * on [Song.id] at import time.
 *
 * [songs] is stored in the order the user imported them. What the UI shows is
 * this list after [sortedByName], so the stored order doubles as the "restore
 * my own order" reference.
 */
@Immutable
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val createdAt: Long = 0L,
) {
    fun withSongs(newSongs: List<Song>): Playlist = copy(songs = newSongs)

    fun addSongs(incoming: List<Song>): Playlist {
        if (incoming.isEmpty()) return this
        val seen = songs.mapTo(HashSet()) { it.id }
        val merged = songs + incoming.filter { seen.add(it.id) }
        return if (merged.size == songs.size) this else copy(songs = merged)
    }

    fun removeSong(songId: String): Playlist {
        val remaining = songs.filterNot { it.id == songId }
        return if (remaining.size == songs.size) this else copy(songs = remaining)
    }

    /**
     * Drops several songs at once.
     *
     * Batched rather than looping [removeSong]: a rescan can find dozens of
     * stale entries, and each individual removal would rebuild the list, the
     * queue and the sort.
     */
    fun removeSongs(songIds: Set<String>): Playlist {
        if (songIds.isEmpty()) return this
        val remaining = songs.filterNot { it.id in songIds }
        return if (remaining.size == songs.size) this else copy(songs = remaining)
    }

    /** Replaces metadata for a song that has just been probed, keeping it in place. */
    /**
     * Repairs every song whose stored title or artist is really its own path.
     *
     * [Song.withoutLeakedPathMetadata] says what that means and how such a value
     * is told apart from a real tag. Applied to the songs as they are loaded,
     * before anything derives a display order from them, so the queue and the
     * next save see the repaired values rather than only the rows on screen.
     */
    fun withoutLeakedPathMetadata(): Playlist =
        copy(songs = songs.map { it.withoutLeakedPathMetadata() })

    /** Replaces metadata for a song that has just been probed, keeping it in place. */
    fun updateSong(updated: Song): Playlist {
        val index = songs.indexOfFirst { it.id == updated.id }
        if (index < 0) return this
        if (songs[index] == updated) return this
        return copy(songs = songs.toMutableList().also { it[index] = updated })
    }
}
