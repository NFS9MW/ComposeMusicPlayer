package com.rhp.mediaplayer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Search has to be predictable more than it has to be clever: the whole point of
 * typing into the box is to stop reading the list. These pin down what it
 * matches, and -- just as importantly -- what it does not.
 */
class SongSearchTest {

    private fun song(title: String, artist: String? = null) = Song(
        id = "/music/$title.mp3",
        path = "/music/$title.mp3",
        title = title,
        artist = artist,
    )

    private val library = listOf(
        song("稻香", "周杰伦"),
        song("七里香", "周杰伦"),
        song("Blue in Green", "Miles Davis"),
        song("Amber Light", "Nova"),
        song("Untitled"),
    )

    private fun titles(query: String) = filterBySearchQuery(library, query).map { it.title }

    @Test
    fun `a blank query keeps everything`() {
        assertEquals(library.size, filterBySearchQuery(library, "").size)
        assertEquals(library.size, filterBySearchQuery(library, "   ").size)
    }

    @Test
    fun `matches the title regardless of case`() {
        assertEquals(listOf("Blue in Green"), titles("blue"))
        assertEquals(listOf("Blue in Green"), titles("BLUE"))
        assertEquals(listOf("Amber Light"), titles("amber"))
    }

    @Test
    fun `matches anywhere in the title, not just the start`() {
        assertEquals(listOf("Blue in Green"), titles("green"))
    }

    @Test
    fun `matches the artist`() {
        assertEquals(listOf("稻香", "七里香"), titles("周杰伦"))
        assertEquals(listOf("Blue in Green"), titles("miles davis"))
    }

    @Test
    fun `matches Chinese titles by substring`() {
        assertEquals(listOf("稻香"), titles("稻"))
        assertEquals(listOf("稻香", "七里香"), titles("香"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(listOf("Blue in Green"), titles("  blue  "))
    }

    @Test
    fun `something absent matches nothing`() {
        assertTrue(titles("zzzz").isEmpty())
    }

    @Test
    fun `a song with no artist still matches on its title and does not throw`() {
        assertEquals(listOf("Untitled"), titles("untitled"))
        assertTrue(titles("nobody").isEmpty())
    }

    @Test
    fun `matching does not spill over to the album`() {
        val withAlbum = listOf(
            Song(id = "/a.mp3", path = "/a.mp3", title = "Track One", album = "Kind of Blue"),
        )
        // Album is not a searchable field; only title and artist are.
        assertTrue(filterBySearchQuery(withAlbum, "kind of blue").isEmpty())
        assertEquals(1, filterBySearchQuery(withAlbum, "track").size)
    }
}
