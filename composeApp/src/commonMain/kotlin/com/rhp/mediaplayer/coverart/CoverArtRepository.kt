package com.rhp.mediaplayer.coverart

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Supplies album art on demand.
 *
 * Reading art costs a decoder process per file, so this is explicitly a
 * pull-based cache rather than something the scanner populates: the UI asks for
 * what it is about to draw and nothing else.
 *
 * [get] is meant to be called from composition. Because the backing store is
 * snapshot-aware, reading a missing id subscribes to that id, so the bitmap
 * appearing later recomposes only the rows that wanted it.
 */
interface CoverArtRepository {
    /** Thumbnail for [songId], or null while it is still unknown. */
    operator fun get(songId: String): ImageBitmap?

    /** Asks for a thumbnail for [songId]. Cheap and safe to call every frame. */
    fun request(songId: String, path: String)

    /** Larger artwork for the now-playing view, loaded one track at a time. */
    val largeArt: ImageBitmap?

    /** Asks for the large artwork for the track that is playing now. */
    fun requestLarge(songId: String, path: String)

    /** Drops everything. Called when the library is replaced. */
    fun clear()
}
