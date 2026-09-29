package com.rhp.mediaplayer.audio

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The ffmpeg / ffprobe pair the engine shells out to.
 *
 * [source] records which strategy found them, which is what the startup
 * diagnostic shows when something is wrong — "not found" is a much less useful
 * message than "looked in these four places".
 */
data class FfmpegBinaries(
    val ffmpeg: Path,
    val ffprobe: Path,
    val source: String,
) {
    override fun toString(): String = "ffmpeg=$ffmpeg (found via $source)"

    companion object {
        /** Directory holding a staged per-platform build, e.g. "windows-x64". */
        const val OVERRIDE_PROPERTY = "composemusicplayer.ffmpeg.dir"
        const val OVERRIDE_ENV = "COMPOSE_MUSIC_PLAYER_FFMPEG_DIR"

        @Volatile
        private var cached: FfmpegBinaries? = null

        @Volatile
        private var alreadySearched = false

        /** Locates the binaries once and remembers the answer. */
        fun locate(): FfmpegBinaries? {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                if (alreadySearched) return null
                alreadySearched = true
                val found = search()
                cached = found
                return found
            }
        }

        /** Locates the binaries, reporting every place that was tried. */
        fun locateOrDiagnose(): Pair<FfmpegBinaries?, List<String>> {
            val attempts = mutableListOf<String>()
            val found = search(attempts::add)
            if (found != null) cached = found
            return found to attempts
        }

        private fun search(report: ((String) -> Unit)? = null): FfmpegBinaries? {
            val platform = platformKey()
            if (platform == null) {
                report?.invoke("unsupported platform: ${System.getProperty("os.name")}")
                return null
            }
            val ffmpegName = if (isWindows()) "ffmpeg.exe" else "ffmpeg"
            val ffprobeName = if (isWindows()) "ffprobe.exe" else "ffprobe"

            for ((root, label) in searchRoots(platform)) {
                for (candidate in rootCandidates(root, platform)) {
                    val ffmpeg = candidate.resolve(ffmpegName)
                    val ffprobe = candidate.resolve(ffprobeName)
                    if (isUsable(ffmpeg) && isUsable(ffprobe)) {
                        val source = "$label -> $candidate"
                        report?.invoke("found: $source")
                        return FfmpegBinaries(ffmpeg.toAbsolutePath(), ffprobe.toAbsolutePath(), source)
                    }
                }
                report?.invoke("miss: $label ($root)")
            }

            // Last resort: whatever is on PATH. Convenient on Linux, where the
            // distribution package is always the better-maintained copy.
            for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparator)) {
                if (dir.isBlank()) continue
                val candidate = Paths.get(dir)
                val ffmpeg = candidate.resolve(ffmpegName)
                val ffprobe = candidate.resolve(ffprobeName)
                if (isUsable(ffmpeg) && isUsable(ffprobe)) {
                    report?.invoke("found: PATH -> $candidate")
                    return FfmpegBinaries(ffmpeg.toAbsolutePath(), ffprobe.toAbsolutePath(), "PATH")
                }
            }
            report?.invoke("miss: PATH")
            return null
        }

        private fun searchRoots(platform: String): List<Pair<Path, String>> {
            val roots = mutableListOf<Pair<Path, String>>()

            // 1. Explicit override, for development and for users who want to
            //    point the app at their own build.
            val override = System.getProperty(OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: System.getenv(OVERRIDE_ENV)?.takeIf { it.isNotBlank() }
            if (override != null) {
                roots += Paths.get(override) to "override"
            }

            // 2. Where jpackage puts appResourcesRootDir in a packaged build.
            System.getProperty("compose.application.resources.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let { roots += Paths.get(it) to "packaged resources" }

            // 3. The staged build in the repository, so `gradlew run` works.
            //    Walk up a little because the working directory is the module
            //    directory under Gradle but the project root from an IDE.
            var dir: Path? = Paths.get("").toAbsolutePath()
            repeat(4) {
                val current = dir ?: return@repeat
                roots += current.resolve("third_party").resolve("ffmpeg").resolve(platform) to
                    "dev build in $current"
                dir = current.parent
            }
            return roots
        }

        /** A root may contain the binaries directly, or one level down. */
        private fun rootCandidates(root: Path, platform: String): List<Path> = listOf(
            root,
            root.resolve("ffmpeg"),
            root.resolve(platform),
            root.resolve("ffmpeg").resolve(platform),
        )

        private fun isUsable(path: Path): Boolean =
            Files.isRegularFile(path) && Files.isExecutable(path)

        fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("win")

        /** Matches the directory names used by scripts/fetch_ffmpeg.py. */
        fun platformKey(): String? {
            val os = System.getProperty("os.name").lowercase()
            val osKey = when {
                os.contains("win") -> "windows"
                os.contains("linux") -> "linux"
                else -> return null
            }
            val archKey = when (System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                else -> System.getProperty("os.arch").lowercase()
            }
            return "$osKey-$archKey"
        }
    }
}
