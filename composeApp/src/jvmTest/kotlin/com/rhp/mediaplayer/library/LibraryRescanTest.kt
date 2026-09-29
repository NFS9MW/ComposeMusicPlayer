package com.rhp.mediaplayer.library

import com.rhp.mediaplayer.metadata.AudioMetadata
import com.rhp.mediaplayer.metadata.MetadataReader
import com.rhp.mediaplayer.model.Song
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rescan check, run against a real filesystem.
 *
 * The check itself is one line, so what is worth pinning down is the definition
 * it uses: what counts as "gone", and that the songs still present are left
 * alone. The reader is stubbed to fail on use, which is how the "existence
 * only" half of the contract is held in place -- a rescan must not turn into a
 * second import.
 */
class LibraryRescanTest {

    private val directories = mutableListOf<Path>()

    private val forbiddenReader = object : MetadataReader {
        override fun read(path: String): AudioMetadata =
            error("a rescan must not read tags, but it probed $path")

        override fun readCoverArt(path: String): ByteArray =
            error("a rescan must not read cover art, but it probed $path")
    }

    private val service = DesktopLibraryService(forbiddenReader)

    @AfterTest
    fun cleanUp() {
        directories.forEach { root ->
            runCatching {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        directories.clear()
    }

    @Test
    fun `reports exactly the songs whose file is gone`() {
        val directory = tempDirectory()
        val kept = songAt(directory.resolve("kept.mp3"), create = true)
        val removed = songAt(directory.resolve("removed.flac"), create = true)

        Files.delete(removed.pathAsPath())

        val missing = runBlocking { service.findMissingFiles(listOf(kept, removed)) }

        assertEquals(listOf(removed.id), missing.map { it.id })
    }

    @Test
    fun `a path that has become a directory counts as gone`() {
        val directory = tempDirectory()
        val path = directory.resolve("was-a-file.mp3")
        val song = songAt(path, create = true)

        // Replacing a file with a directory of the same name is unusual but
        // real, and the entry is just as unplayable either way.
        Files.delete(path)
        Files.createDirectory(path)

        val missing = runBlocking { service.findMissingFiles(listOf(song)) }

        assertEquals(listOf(song.id), missing.map { it.id })
    }

    @Test
    fun `nothing is reported when every file is still there`() {
        val directory = tempDirectory()
        val songs = listOf("a.mp3", "b.mp3", "c.mp3").map { name ->
            songAt(directory.resolve(name), create = true)
        }

        val missing = runBlocking { service.findMissingFiles(songs) }

        assertTrue(missing.isEmpty(), "expected no stale entries, got $missing")
    }

    @Test
    fun `an empty playlist is reported as nothing missing`() {
        assertTrue(runBlocking { service.findMissingFiles(emptyList()) }.isEmpty())
    }

    private fun tempDirectory(): Path =
        Files.createTempDirectory("rescan-test").also { directories.add(it) }

    private fun songAt(path: Path, create: Boolean): Song {
        if (create) Files.writeString(path, "")
        return Song.fromPath(path.toString())
    }

    private fun Song.pathAsPath(): Path = Path.of(path)
}
