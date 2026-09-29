package com.rhp.mediaplayer.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ordering tests use [TextCollator.Natural] rather than the real locale-aware
 * collator, and that is the point: this file pins down the parts that are ours
 * -- name normalization and the tie-breaks -- while leaving pinyin behaviour to
 * the JVM implementation, which is not something a unit test should re-verify.
 */
class SongSortingTest {

    private fun song(title: String, artist: String? = null, path: String? = null) = Song(
        id = path ?: "/music/$title.mp3",
        path = path ?: "/music/$title.mp3",
        title = title,
        artist = artist,
    )

    private fun titles(songs: List<Song>) = songs.map { it.title }

    @Test
    fun `normalization folds case and drops a leading article`() {
        assertEquals("beatles", normalizeSortText("The Beatles"))
        assertEquals("beatles", normalizeSortText("the beatles"))
        assertEquals("beatles", normalizeSortText("  THE BEATLES  "))
        assertEquals("beatles", normalizeSortText("Beatles"))
    }

    @Test
    fun `normalization does not eat words that merely start with the`() {
        assertEquals("theatre", normalizeSortText("Theatre"))
        assertEquals("theremin", normalizeSortText("Theremin"))
        assertEquals("the", normalizeSortText("The"))
    }

    @Test
    fun `sorts by title`() {
        val sorted = sortedByName(
            listOf(song("Cyan"), song("Amber"), song("Blue")),
            TextCollator.Natural,
        )
        assertEquals(listOf("Amber", "Blue", "Cyan"), titles(sorted))
    }

    @Test
    fun `articles do not pin a song to the top of the list`() {
        val sorted = sortedByName(
            listOf(song("The Zoo"), song("Apple"), song("The Anchor")),
            TextCollator.Natural,
        )
        assertEquals(listOf("The Anchor", "Apple", "The Zoo"), titles(sorted))
    }

    @Test
    fun `equal titles fall back to artist`() {
        val sorted = sortedByName(
            listOf(song("Intro", "Zimmer"), song("Intro", "Bach")),
            TextCollator.Natural,
        )
        assertEquals(listOf("Bach", "Zimmer"), sorted.map { it.artist })
    }

    @Test
    fun `equal titles and artists fall back to path so the order is stable`() {
        val first = song("Intro", "Bach", path = "/a/intro.mp3")
        val second = song("Intro", "Bach", path = "/b/intro.mp3")

        assertEquals(
            listOf(first, second),
            sortedByName(listOf(second, first), TextCollator.Natural),
        )
        // Idempotent: sorting an already sorted list must not move anything.
        assertEquals(
            listOf(first, second),
            sortedByName(listOf(first, second), TextCollator.Natural),
        )
    }

    @Test
    fun `descending reverses the whole comparison`() {
        val ascending = sortedByName(
            listOf(song("Cyan"), song("Amber"), song("Blue")),
            TextCollator.Natural,
        )
        val descending = sortedByName(
            listOf(song("Cyan"), song("Amber"), song("Blue")),
            TextCollator.Natural,
            SortDirection.Descending,
        )
        assertEquals(listOf("Amber", "Blue", "Cyan"), titles(ascending))
        assertEquals(listOf("Cyan", "Blue", "Amber"), titles(descending))
    }

    @Test
    fun `direction flips`() {
        assertEquals(SortDirection.Descending, SortDirection.Ascending.flipped())
        assertEquals(SortDirection.Ascending, SortDirection.Descending.flipped())
    }

    @Test
    fun `songs without artist sort before those with one at equal title`() {
        val sorted = sortedByName(
            listOf(song("Intro", "Bach"), song("Intro")),
            TextCollator.Natural,
        )
        assertEquals(null, sorted.first().artist)
    }

    @Test
    fun `empty and single inputs pass through`() {
        assertEquals(emptyList(), sortedByName(emptyList(), TextCollator.Natural))
        val one = listOf(song("Only"))
        assertEquals(one, sortedByName(one, TextCollator.Natural))
    }
}
