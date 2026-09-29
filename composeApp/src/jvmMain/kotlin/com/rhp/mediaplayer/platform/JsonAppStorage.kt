package com.rhp.mediaplayer.platform

import com.rhp.mediaplayer.settings.AppStorage
import com.rhp.mediaplayer.settings.PersistedLibrary
import com.rhp.mediaplayer.settings.PlayerSettings
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists settings and playlists as JSON in the per-user config directory.
 *
 * Failure is handled by degrading rather than by throwing. A music player that
 * refuses to start because its own preferences file got truncated -- by a crash,
 * a full disk, or a user editing it by hand -- is worse than one that starts
 * fresh, so an unreadable file is moved aside and defaults are used. Nothing is
 * deleted silently: the corrupt copy stays on disk for inspection.
 */
class JsonAppStorage(
    private val directory: Path = AppPaths.configDirectory,
) : AppStorage {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadLibrary(): PersistedLibrary = read(LIBRARY_FILE, PersistedLibrary())

    override fun saveLibrary(library: PersistedLibrary) = write(LIBRARY_FILE, library)

    override fun loadSettings(): PlayerSettings =
        read(SETTINGS_FILE, PlayerSettings()).normalized()

    override fun saveSettings(settings: PlayerSettings) =
        write(SETTINGS_FILE, settings.normalized())

    private inline fun <reified T> read(name: String, fallback: T): T {
        val file = directory.resolve(name)
        if (!Files.isRegularFile(file)) return fallback
        return try {
            json.decodeFromString<T>(Files.readString(file))
        } catch (_: Exception) {
            quarantine(file)
            fallback
        }
    }

    private inline fun <reified T> write(name: String, value: T) {
        val target = directory.resolve(name)
        try {
            Files.createDirectories(directory)
            // Write beside the target and move into place, so a crash mid-write
            // cannot leave a half-written file where the real one used to be.
            val temporary = directory.resolve("$name.tmp")
            Files.writeString(temporary, json.encodeToString(value))
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // Losing a preferences write is not worth interrupting playback for.
        }
    }

    private fun quarantine(file: Path) {
        runCatching {
            val stamp = System.currentTimeMillis()
            Files.move(
                file,
                file.resolveSibling("${file.fileName}.corrupt-$stamp"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val LIBRARY_FILE = "library.json"
        const val SETTINGS_FILE = "settings.json"
    }
}
