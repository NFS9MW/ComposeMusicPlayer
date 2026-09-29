package com.rhp.mediaplayer.platform

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Native-ish file and folder selection through Swing's JFileChooser.
 *
 * Deliberately the JDK one rather than a third-party picker: it needs no
 * dependency, it is the dialog users already know on both Windows and Linux,
 * and it is the only one that can hand back a *directory* to be scanned
 * recursively, which is how most people import a library.
 *
 * The chooser must be shown on the event dispatch thread. Compose Desktop's main
 * thread already is the EDT, so the common path shows the dialog directly; the
 * off-EDT path exists for callers running on a coroutine dispatcher.
 */
object FilePicker {

    /** Multi-select over audio files. Returns an empty list when cancelled. */
    fun chooseAudioFiles(initialDirectory: File?): List<File> =
        onEventDispatchThread {
            buildChooser(initialDirectory, directoriesOnly = false).let { chooser ->
                val audioFilter = FileNameExtensionFilter(
                    "音频文件",
                    *AUDIO_FILTER_EXTENSIONS,
                )
                chooser.addChoosableFileFilter(audioFilter)
                chooser.fileFilter = audioFilter
                chooser.isMultiSelectionEnabled = true

                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFiles?.toList().orEmpty()
                } else {
                    emptyList()
                }
            }
        }

    /** Single directory, to be scanned recursively. Returns null when cancelled. */
    fun chooseDirectory(initialDirectory: File?): File? =
        onEventDispatchThread {
            val chooser = buildChooser(initialDirectory, directoriesOnly = true)
            chooser.isMultiSelectionEnabled = false
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.takeIf { it.isDirectory }
            } else {
                null
            }
        }

    private fun buildChooser(initialDirectory: File?, directoriesOnly: Boolean) = JFileChooser().apply {
        dialogTitle = if (directoriesOnly) "选择要扫描的文件夹" else "选择音乐文件"
        isAcceptAllFileFilterUsed = true
        fileSelectionMode = if (directoriesOnly) {
            JFileChooser.DIRECTORIES_ONLY
        } else {
            JFileChooser.FILES_ONLY
        }
        currentDirectory = initialDirectory?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home") ?: ".")
    }

    private fun <T> onEventDispatchThread(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()

        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Extensions offered in the file dialog's filter dropdown. */
    private val AUDIO_FILTER_EXTENSIONS = arrayOf(
        "mp3", "flac", "wav", "aif", "aiff", "m4a", "aac", "ogg", "oga", "opus", "wma", "ape", "wv",
    )
}
