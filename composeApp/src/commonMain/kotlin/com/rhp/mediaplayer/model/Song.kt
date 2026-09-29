package com.rhp.mediaplayer.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One audio file. [id] is the absolute file path, which is unique per file and
 * stable across restarts, so it doubles as the identity used for list keys and
 * for keeping the current track across a re-sort.
 *
 * Metadata is filled in asynchronously: a song starts out with just a path and
 * a provisional title derived from the file name, and gains artist/album/
 * duration once ffprobe has answered. That is why [durationMs] is nullable --
 * the UI shows an indeterminate duration until it is known rather than
 * blocking the list on metadata.
 */
@Immutable
@Serializable
data class Song(
    val id: String,
    val path: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
) {
    val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: UnknownArtist

    /**
     * Drops a title or artist that is really this song's own location.
     *
     * Libraries written before the file name was split off correctly hold the
     * whole path as the title, and the part in front of " - " as the artist, for
     * every file whose tags are missing. Read back, those entries list a
     * directory where a name belongs and cannot recover on their own: the stored
     * value looks like an ordinary tag, and a rescan deliberately leaves titles
     * alone. What gives them away is that the song's own path starts with them
     * -- a real tag is not a prefix of where its file happens to live.
     *
     * Only asked of values carrying a separator, because a leaked one always
     * carries a directory while a tag merely sometimes does:
     * "God Rest Ye Merry Gentlemen/We Three Kings" is a title someone has, and
     * it does not start its file's path.
     */
    fun withoutLeakedPathMetadata(): Song {
        val titleLeaked = leaksItsOwnPath(title)
        val artistLeaked = artist?.let(::leaksItsOwnPath) == true
        if (!titleLeaked && !artistLeaked) return this
        return copy(
            title = if (titleLeaked) titleFromFileName(path) else title,
            artist = if (artistLeaked) artistFromFileName(path) else artist,
        )
    }

    private fun leaksItsOwnPath(value: String): Boolean {
        if (value.isEmpty()) return false
        if (!value.contains('/') && !value.contains('\\')) return false
        return path.startsWith(value)
    }

    companion object {
        const val UnknownArtist = "未知艺术家"

        /** A song known only by its path, with a best-effort title. */
        fun fromPath(path: String): Song = Song(
            id = path,
            path = path,
            title = titleFromFileName(path),
        )

        /**
         * Derives a title from a bare file name.
         *
         * Files in the wild are commonly named "Artist - Title", so that shape is
         * honoured; anything else is used verbatim. The extension is always dropped.
         */
        fun titleFromFileName(path: String): String {
            val name = fileNameOf(path)
            val stem = name.substringBeforeLast('.', name).trim()
            if (stem.isEmpty()) return name
            val dash = stem.indexOf(" - ")
            if (dash > 0 && dash + 3 < stem.length) {
                val candidate = stem.substring(dash + 3).trim()
                if (candidate.isNotEmpty()) return candidate
            }
            return stem
        }

        /** Derives the artist from an "Artist - Title" file name, if it has that shape. */
        fun artistFromFileName(path: String): String? {
            val name = fileNameOf(path)
            val stem = name.substringBeforeLast('.', name).trim()
            val dash = stem.indexOf(" - ")
            if (dash <= 0) return null
            return stem.substring(0, dash).trim().takeIf { it.isNotEmpty() }
        }

        /**
         * The last segment of [path], with no directory in front of it.
         *
         * Both separators are checked separately. Passing them to
         * `substringAfterLast` as one string would look for the two-character
         * sequence "/\" -- which no path contains -- and quietly hand back the
         * whole path, so a file with no readable tags would be listed under its
         * full location instead of its name. Windows paths and POSIX paths are
         * both accepted because a library can outlive the machine it was built
         * on, and a path can arrive from a playlist file rather than from a
         * scan.
         */
        private fun fileNameOf(path: String): String {
            val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (cut < 0) path else path.substring(cut + 1)
        }
    }
}
