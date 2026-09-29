package com.rhp.mediaplayer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Filling in a title and an artist when there are no usable tags.
 *
 * These run for every file whose tags are missing or unreadable, so the failure
 * they guard against is not subtle: a path that is not split at all reaches the
 * list as the file's whole location, and the list is then sorted by it -- so
 * every untagged file piles up under "/" or "C:" instead of under its own name.
 *
 * Both separator styles are covered deliberately. The bug this replaced came
 * from treating "/" and "\" as one two-character delimiter, which matches
 * neither a POSIX path nor a Windows one.
 */
class SongNamingTest {

    @Test
    fun `drops the directory from a POSIX path`() {
        assertEquals("Finders X", Song.titleFromFileName("/home/rhp/音乐/Finders X.mp3"))
    }

    @Test
    fun `drops the directory from a Windows path`() {
        assertEquals("Finders X", Song.titleFromFileName("""H:\Music\Finders X.flac"""))
    }

    @Test
    fun `splits artist and title on a POSIX path`() {
        val path = "/home/rhp/音乐/周杰伦 - 稻香.mp3"
        assertEquals("稻香", Song.titleFromFileName(path))
        assertEquals("周杰伦", Song.artistFromFileName(path))
    }

    @Test
    fun `splits artist and title on a Windows path`() {
        val path = """H:\Music\Aurora Field - Filler 01.mp3"""
        assertEquals("Filler 01", Song.titleFromFileName(path))
        assertEquals("Aurora Field", Song.artistFromFileName(path))
    }

    @Test
    fun `a name with no directory is its own file name`() {
        assertEquals("Finders X", Song.titleFromFileName("Finders X.mp3"))
        assertNull(Song.artistFromFileName("Finders X.mp3"))
    }

    @Test
    fun `only the last extension is dropped`() {
        assertEquals("track.title", Song.titleFromFileName("/m/track.title.mp3"))
    }

    @Test
    fun `a dot in a directory name is not mistaken for an extension`() {
        // The directory has to be split off before the extension is looked for,
        // or this yields "b/song".
        assertEquals("song", Song.titleFromFileName("/home/a.b/song.mp3"))
    }

    @Test
    fun `a leading article is left alone, since sorting removes it`() {
        assertEquals("The Anchor", Song.titleFromFileName("/m/The Anchor.mp3"))
    }

    @Test
    fun `a dash is only a separator when it has something on both sides`() {
        // No spaces around it: not the "Artist - Title" shape.
        assertEquals("Song-without-spaces", Song.titleFromFileName("/m/Song-without-spaces.mp3"))
        // At the very start there is no artist in front of it, so the name is
        // left as it is rather than being cut down to nothing.
        assertEquals("- Solo", Song.titleFromFileName("/m/ - Solo.mp3"))
        assertNull(Song.artistFromFileName("/m/ - Solo.mp3"))
    }

    @Test
    fun `fromPath builds a song titled after the file`() {
        val song = Song.fromPath("/home/rhp/音乐/Finders X.mp3")
        assertEquals("/home/rhp/音乐/Finders X.mp3", song.id)
        assertEquals("Finders X", song.title)
    }
}
