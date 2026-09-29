package com.rhp.mediaplayer.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the app keeps its own files.
 *
 * Follows each platform's convention rather than dumping a dot-directory in the
 * home folder: `%APPDATA%` on Windows, `$XDG_CONFIG_HOME` (or `~/.config`) on
 * Linux. Falling back to the home directory keeps this working in stripped-down
 * environments where those variables are unset.
 */
object AppPaths {

    private const val APP_DIRECTORY = "ComposeMusicPlayer"

    /** Where state lived before the app was renamed. Read once, then moved. */
    private const val LEGACY_APP_DIRECTORY = "ComposeMediaPlayer"

    /**
     * Overrides where state is kept. Used by development runs and tests so they
     * never read or write the real user profile.
     */
    const val OVERRIDE_PROPERTY = "composemusicplayer.config.dir"
    const val OVERRIDE_ENV = "COMPOSE_MUSIC_PLAYER_CONFIG_DIR"

    val configDirectory: Path by lazy {
        val override = System.getProperty(OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(OVERRIDE_ENV)?.takeIf { it.isNotBlank() }
        if (override != null) {
            return@lazy Paths.get(override).also { ensureExists(it) }
        }

        val base = when {
            isWindows() -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: userHome().resolve("AppData").resolve("Roaming").toString()

            else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: userHome().resolve(".config").toString()
        }
        Paths.get(base).resolve(APP_DIRECTORY).also { directory ->
            adoptLegacyDirectory(directory)
            ensureExists(directory)
        }
    }

    /**
     * Moves a library saved under the app's previous name into the new one.
     *
     * Renaming the app moved where its state lives, and opening to an empty
     * library is a poor way to discover that. Only ever consulted when the new
     * directory does not exist yet, so this can never overwrite a real library
     * -- and it is a move rather than a copy, because both directories share a
     * parent and a rename is free.
     */
    private fun adoptLegacyDirectory(target: Path) {
        if (Files.exists(target)) return
        val parent = target.parent ?: return
        val legacy = parent.resolve(LEGACY_APP_DIRECTORY)
        if (!Files.isDirectory(legacy)) return
        runCatching { Files.move(legacy, target) }
    }

    private fun ensureExists(directory: Path) {
        runCatching { Files.createDirectories(directory) }
    }

    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun userHome(): Path = Paths.get(System.getProperty("user.home"))
}
