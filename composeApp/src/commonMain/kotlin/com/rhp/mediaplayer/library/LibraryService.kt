package com.rhp.mediaplayer.library

import com.rhp.mediaplayer.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * The platform's library capabilities: showing a file dialog, walking folders,
 * and reading tags.
 *
 * This exists so [com.rhp.mediaplayer.player.PlayerController] can stay free
 * of platform imports. The controller is the piece with all the policy in it --
 * which playlist is selected, when the queue is rebuilt, how imports are
 * batched -- and keeping that testable is worth one interface.
 */
interface LibraryService {
    /**
     * Shows a multi-select file dialog. Returns the chosen absolute paths, or an
     * empty list when the user cancelled.
     *
     * [initialDirectory] is where the dialog opens; null means the platform default.
     */
    fun chooseAudioFiles(initialDirectory: String?): List<String>

    /** Shows a folder dialog. Returns null when cancelled. */
    fun chooseDirectory(initialDirectory: String?): String?

    /**
     * Expands files and directories into song stubs.
     *
     * Cheap: no decoding, titles are guessed from file names. Directories are
     * walked recursively.
     */
    fun discover(paths: List<String>): List<Song>

    /** Probes tags, emitting each song as soon as its metadata resolves. */
    fun metadata(songs: List<Song>): Flow<Song>

    /**
     * Reports which of [songs] no longer have a file behind them.
     *
     * Existence only: nothing is decoded and no tags are read. A rescan is
     * about entries that have gone stale, and re-probing every file would turn
     * an instant check into a long one for no gain.
     *
     * Suspending rather than blocking, because a library on a network share
     * turns each of these checks into I/O that has no business running on the
     * thread drawing the list.
     */
    suspend fun findMissingFiles(songs: List<Song>): List<Song>

    /**
     * Directory containing [path], or null if it has none.
     *
     * Lives here because "what is a path's parent" is a platform question, and
     * the controller needs the answer to remember where the chooser should
     * reopen next time.
     */
    fun parentDirectory(path: String): String?
}
