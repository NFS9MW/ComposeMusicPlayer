package com.rhp.mediaplayer.library

import com.rhp.mediaplayer.metadata.MetadataReader
import com.rhp.mediaplayer.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Turns directories into songs, then fills in their metadata.
 *
 * The two steps are deliberately separate. Discovery is cheap and local, so the
 * list can be shown immediately with titles guessed from file names; probing
 * tags means running a decoder process per file, which for a real library takes
 * long enough that it has to be progressive. The UI therefore renders rows
 * first and watches them fill in.
 *
 * Probing runs with bounded parallelism: these are subprocesses that spend
 * almost all their time waiting, so a handful in flight keeps the CPU busy
 * without spawning hundreds of processes at once.
 */
class MediaScanner(
    private val reader: MetadataReader,
    private val parallelism: Int = 6,
) {

    /**
     * Expands the given files and directories into song stubs.
     *
     * Directories are walked recursively. Unreadable subdirectories are skipped
     * rather than aborting the scan -- permission-denied somewhere deep in a
     * tree should not cost the user the hundreds of files that are readable.
     */
    fun discover(inputs: List<File>): List<Song> {
        val paths = LinkedHashSet<String>()

        for (input in inputs) {
            when {
                input.isDirectory -> collectFrom(input.toPath(), paths)
                input.isFile && isAudioFile(input.name) -> paths += input.absolutePath
            }
        }

        // Sorted so a repeated import produces a stable list, which in turn
        // keeps the natural order (and therefore the shuffle) reproducible.
        return paths.sorted().map(::toSong)
    }

    /**
     * Probes each song and emits it as soon as its tags are known.
     *
     * Songs that cannot be probed are simply not emitted; the caller keeps the
     * filename-derived fallback it already has.
     */
    fun metadata(songs: List<Song>): Flow<Song> = channelFlow {
        val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)
        for (song in songs) {
            launch(dispatcher) {
                val metadata = try {
                    reader.read(song.path)
                } catch (_: Exception) {
                    null
                } ?: return@launch
                send(merge(song, metadata))
            }
        }
    }

    private fun collectFrom(root: Path, sink: MutableSet<String>) {
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val name = dir.fileName?.toString().orEmpty()
                        return if (dir != root && name.startsWith(".")) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName?.toString().orEmpty()
                        if (attrs.isRegularFile && isAudioFile(name) && !isHiddenNoise(name)) {
                            sink += file.toAbsolutePath().toString()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        // Unreadable entry: keep walking instead of giving up.
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    private fun toSong(path: String): Song = Song(
        id = path,
        path = path,
        title = Song.titleFromFileName(path),
        artist = Song.artistFromFileName(path),
    )

    /** Folds probed tags onto the stub, keeping the filename fallbacks. */
    private fun merge(song: Song, metadata: com.rhp.mediaplayer.metadata.AudioMetadata): Song = song.copy(
        title = metadata.title?.takeIf { it.isNotBlank() } ?: song.title,
        artist = metadata.artist?.takeIf { it.isNotBlank() } ?: song.artist,
        album = metadata.album?.takeIf { it.isNotBlank() },
        durationMs = metadata.durationMs,
        trackNumber = metadata.trackNumber,
    )

    companion object {
        /**
         * Formats ffmpeg can decode that are plausibly music.
         *
         * Video containers are deliberately absent: a music player that pulls
         * films into the library because both are MP4 is not helping anyone.
         */
        val AUDIO_EXTENSIONS: Set<String> = setOf(
            "mp3", "mp2", "mpga",
            "flac",
            "wav", "wave",
            "aif", "aiff", "aifc",
            "au", "snd",
            "m4a", "m4b", "m4p", "aac",
            "ogg", "oga", "opus",
            "wma", "ape", "wv", "tta", "mpc",
            "dsf", "dff",
            "mka", "m3u", "cue", // parsed away below; kept out of the walk
        )

        fun isAudioFile(name: String): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return false
            if (extension == "m3u" || extension == "cue") return false
            return extension in AUDIO_EXTENSIONS
        }

        /** macOS resource forks and similar clutter that shadows real files. */
        private fun isHiddenNoise(name: String): Boolean =
            name.startsWith("._") || name.startsWith(".")
    }
}
