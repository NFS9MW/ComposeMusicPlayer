package com.rhp.mediaplayer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Repairing titles and artists that were stored as the file's own path.
 *
 * Such entries come from libraries written before the file name was split off
 * correctly: every file with no readable tags kept its whole location as the
 * title, and the part in front of " - " as the artist. Nothing else revisits a
 * stored name -- a rescan checks that files still exist and deliberately leaves
 * titles alone -- so without this repair those rows list a directory for ever.
 *
 * The negatives matter as much as the positives here. A tag is allowed to
 * contain a separator: "God Rest Ye Merry Gentlemen/We Three Kings" and
 * "melody/summer" are in real libraries. So "contains a separator" is not the
 * test, and getting it wrong would rewrite good names rather than fix bad ones.
 * What identifies a leak is that the song's own path starts with it.
 */
class SongPathLeakTest {

    private fun song(
        path: String,
        title: String,
        artist: String? = null,
        album: String? = null,
    ): Song = Song(id = path, path = path, title = title, artist = artist, album = album)

    @Test
    fun `a title that is the whole path becomes the file name`() {
        val leaked = song("/home/rhp/音乐/Finders X.mp3", "/home/rhp/音乐/Finders X")

        assertEquals("Finders X", leaked.withoutLeakedPathMetadata().title)
    }

    @Test
    fun `an artist that is the path in front of the dash becomes the name in front of it`() {
        val leaked = song(
            path = "/home/rhp/音乐/233取个名字真难 - 111.mp3",
            title = "111",
            artist = "/home/rhp/音乐/233取个名字真难",
        )

        val repaired = leaked.withoutLeakedPathMetadata()
        assertEquals("233取个名字真难", repaired.artist)
        // The title here survived the old bug intact -- running the " - " split
        // over a whole path happens to give the right answer for this shape --
        // and repairing one field must not disturb the other.
        assertEquals("111", repaired.title)
    }

    @Test
    fun `a windows path leaks the same way`() {
        val leaked = song(
            path = """H:\Music\Aurora Field - Filler 01.mp3""",
            title = """H:\Music\Aurora Field - Filler 01""",
            artist = """H:\Music\Aurora Field""",
        )

        val repaired = leaked.withoutLeakedPathMetadata()
        assertEquals("Filler 01", repaired.title)
        assertEquals("Aurora Field", repaired.artist)
    }

    @Test
    fun `a title with a slash in it is a tag, not a leak`() {
        val real = song(
            path = "/home/rhp/音乐/Orla Fallon - God Rest Ye Merry Gentlemen We Three Kings.mp3",
            title = "God Rest Ye Merry Gentlemen/We Three Kings",
            artist = "Orla Fallon",
            album = "The Days / Nights (EP)",
        )

        assertSame(real, real.withoutLeakedPathMetadata())
    }

    @Test
    fun `an artist with a slash in it is a tag, not a leak`() {
        val real = song(
            path = "/home/rhp/音乐/Anjulie,TheFatRat - Fly Away.mp3",
            title = "Fly Away",
            artist = "Anjulie/TheFatRat",
        )

        assertSame(real, real.withoutLeakedPathMetadata())
    }

    @Test
    fun `a value with no directory in it is never a leak`() {
        // A leaked value always carries a directory, so a bare prefix of a
        // relative path is a tag -- and treating it as a leak would drop the
        // artist outright, this file name having no " - " to take one from.
        val real = song(path = "ABBA.mp3", title = "ABBA", artist = "AB")

        assertSame(real, real.withoutLeakedPathMetadata())
    }

    @Test
    fun `a song already named after its file is left alone`() {
        val real = Song.fromPath("/home/rhp/音乐/Finders X.mp3")

        assertSame(real, real.withoutLeakedPathMetadata())
        assertNull(real.artist)
    }

    @Test
    fun `a playlist repairs every song it holds`() {
        val leaked = song("/home/rhp/音乐/Rage Of God.mp3", "/home/rhp/音乐/Rage Of God")
        val real = Song.fromPath("/home/rhp/音乐/Finders X.mp3")

        val repaired = Playlist(id = "p", name = "我的音乐", songs = listOf(leaked, real))
            .withoutLeakedPathMetadata()

        assertEquals(listOf("Rage Of God", "Finders X"), repaired.songs.map { it.title })
    }
}
