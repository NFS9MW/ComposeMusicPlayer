package com.rhp.mediaplayer.library

import com.rhp.mediaplayer.metadata.MetadataReader
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.platform.FilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop implementation: Swing's file chooser plus the ffprobe-backed scanner.
 *
 * Thin on purpose -- it only bridges types, converting the paths the picker
 * returns into the [File]s the scanner wants.
 */
class DesktopLibraryService(
    private val reader: MetadataReader,
    private val parallelism: Int = 6,
) : LibraryService {

    private val scanner = MediaScanner(reader, parallelism)

    override fun chooseAudioFiles(initialDirectory: String?): List<String> =
        FilePicker.chooseAudioFiles(initialDirectory?.let(::File))
            .map { it.absolutePath }

    override fun chooseDirectory(initialDirectory: String?): String? =
        FilePicker.chooseDirectory(initialDirectory?.let(::File))?.absolutePath

    override fun discover(paths: List<String>): List<Song> =
        scanner.discover(paths.map(::File))

    override fun metadata(songs: List<Song>): Flow<Song> = scanner.metadata(songs)

    override suspend fun findMissingFiles(songs: List<Song>): List<Song> =
        withContext(Dispatchers.IO) {
            // isFile rather than exists: a path that has become a directory, or
            // a broken symlink, is just as unplayable as one that is gone.
            songs.filterNot { File(it.path).isFile }
        }

    override fun parentDirectory(path: String): String? = File(path).parent
}
