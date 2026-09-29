package com.rhp.mediaplayer.model

import kotlinx.serialization.Serializable

/**
 * Locale-aware text ordering.
 *
 * This sits behind an interface because collation is platform API and cannot be
 * referenced from common code: the JVM implementation wraps [java.text.Collator]
 * with a Chinese locale, which is what makes "稻香" sort under D (pinyin) rather
 * than under its Unicode code point.
 *
 * Keeping it abstract also lets the queue's ordering be tested against a plain
 * deterministic collator instead of a locale-dependent one.
 */
fun interface TextCollator {
    fun compare(a: String, b: String): Int

    companion object {
        /** Compares by UTF-16 order. Deterministic, but not linguistically correct. */
        val Natural: TextCollator = TextCollator { a, b -> a.compareTo(b) }
    }
}

/** Sort order for the song list. */
@Serializable
enum class SortDirection {
    Ascending,
    Descending;

    fun flipped(): SortDirection = if (this == Ascending) Descending else Ascending
}

private const val LeadingArticle = "the "

/**
 * Folds away the noise that makes an alphabetical listing feel wrong: case, and
 * a leading English article. "The Beatles" belongs under B, not T.
 *
 * Lowercasing here is deliberately locale-independent -- it only serves to make
 * the comparison case-insensitive, while the actual ordering is decided by the
 * [TextCollator] afterwards.
 */
fun normalizeSortText(text: String): String {
    val trimmed = text.trim().lowercase()
    return if (trimmed.startsWith(LeadingArticle)) {
        trimmed.substring(LeadingArticle.length)
    } else {
        trimmed
    }
}

/**
 * Sorts by title, then artist, then path.
 *
 * The trailing tie-breaks matter more than they look: without them two songs
 * sharing a title would order arbitrarily, so the list would silently reshuffle
 * between runs and the stable list keys would fight the animation.
 *
 * Normalized keys are computed once up front rather than inside the comparator,
 * because a comparator runs O(n log n) times and [normalizeSortText] allocates.
 */
fun sortedByName(
    songs: List<Song>,
    collator: TextCollator,
    direction: SortDirection = SortDirection.Ascending,
): List<Song> {
    if (songs.size <= 1) return songs

    class Keyed(val song: Song, val title: String, val artist: String)

    val keyed = songs.map {
        Keyed(it, normalizeSortText(it.title), normalizeSortText(it.artist.orEmpty()))
    }

    val ascending = Comparator<Keyed> { a, b ->
        var result = collator.compare(a.title, b.title)
        if (result == 0) result = collator.compare(a.artist, b.artist)
        if (result == 0) result = a.song.id.compareTo(b.song.id)
        result
    }

    val comparator = if (direction == SortDirection.Ascending) ascending else ascending.reversed()
    return keyed.sortedWith(comparator).map { it.song }
}
