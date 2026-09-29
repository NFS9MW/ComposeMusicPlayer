package com.rhp.mediaplayer.metadata

/**
 * What ffprobe could tell us about a file.
 *
 * Every field is nullable because real libraries are full of files with no
 * tags at all. Callers fill the gaps from the file name rather than inventing
 * values here, so "no metadata" stays distinguishable from "metadata that
 * happens to be empty".
 */
data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val hasEmbeddedArt: Boolean = false,
)

/**
 * Reads tags out of an audio file.
 *
 * Kept as an interface so the scanner does not depend on ffprobe directly:
 * scanning runs against a large number of files, and the implementation is the
 * part worth swapping out if a faster path ever becomes worthwhile.
 */
interface MetadataReader {
    /** Returns null when the file cannot be probed at all. */
    fun read(path: String): AudioMetadata?

    /** Raw bytes of an embedded cover image, or null when the file has none. */
    fun readCoverArt(path: String): ByteArray?
}
