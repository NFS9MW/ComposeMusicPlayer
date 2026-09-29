package com.rhp.mediaplayer.metadata

import com.rhp.mediaplayer.audio.FfmpegBinaries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Tag reading through ffprobe.
 *
 * ffprobe is asked for JSON and the response is walked by hand rather than
 * mapped onto fixed classes. That is deliberate: tag keys vary by container in
 * case and naming ("track" vs "TRACK", "tracknumber"), most fields are declared
 * as strings even when they are numbers, and plenty of real files omit fields
 * entirely. Navigating the document tolerantly is more robust here than a
 * strict schema that throws on the first odd file in someone's library.
 */
class FfprobeMetadataReader(private val binaries: FfmpegBinaries) : MetadataReader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun read(path: String): AudioMetadata? {
        val raw = runProbe(
            listOf(
                binaries.ffprobe.toString(),
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                path,
            ),
        ) ?: return null

        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?: return null

        val format = root.objectOrNull("format")
        val streams = (root["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val audioStream = streams.firstOrNull { it.textOrNull("codec_type") == "audio" }

        // Container-level tags first, then anything only the stream carries.
        val tags = collectTags(format)
            .toMutableMap()
            .also { merged ->
                collectTags(audioStream).forEach { (key, value) ->
                    if (!merged.containsKey(key)) merged[key] = value
                }
            }

        // An attached picture shows up as a video stream flagged in its
        // disposition, which is how cover art is discovered here.
        val hasArt = streams.any { stream ->
            stream.textOrNull("codec_type") == "video" &&
                stream.objectOrNull("disposition")?.textOrNull("attached_pic")?.let { it != "0" } == true
        }

        val durationMs = format?.textOrNull("duration")?.toDoubleOrNull()
            ?: audioStream?.textOrNull("duration")?.toDoubleOrNull()
            ?: format?.textOrNull("DURATION")?.toDoubleOrNull()

        return AudioMetadata(
            title = tags["title"],
            artist = tags["artist"] ?: tags["album_artist"],
            album = tags["album"],
            durationMs = durationMs?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
            trackNumber = parseTrackNumber(tags["track"] ?: tags["tracknumber"]),
            hasEmbeddedArt = hasArt,
        )
    }

    override fun readCoverArt(path: String): ByteArray? {
        // image2pipe hands back the raw bytes of the first video stream, which
        // for an audio file is the embedded cover. Both ffmpeg and ffprobe can
        // be asked for this, but going through the image muxer avoids having to
        // guess the image format from the codec name.
        val bytes = runBinary(
            listOf(
                binaries.ffmpeg.toString(),
                "-v", "error",
                "-nostdin",
                "-i", path,
                "-map", "0:v:0",
                "-an",
                "-c:v", "copy",
                "-f", "image2pipe",
                "-",
            ),
            maxBytes = MAX_ART_BYTES,
        )
        return bytes?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- parsing

    private fun collectTags(element: JsonObject?): Map<String, String> {
        val tags = element?.objectOrNull("tags") ?: return emptyMap()
        val collected = LinkedHashMap<String, String>()
        for ((key, value) in tags) {
            val text = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: continue
            // Keys arrive in whatever case the container used, so normalize on
            // the way in and look everything up in lower case.
            collected[key.lowercase()] = text
        }
        return collected
    }

    /** "3" and "3/12" both mean track 3. */
    private fun parseTrackNumber(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return raw.substringBefore('/').trim().toIntOrNull()
    }

    private fun JsonObject.textOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    // -------------------------------------------------------------- processes

    private fun runProbe(command: List<String>): String? {
        val bytes = runBinary(command, maxBytes = MAX_PROBE_BYTES) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Runs [command], returning stdout.
     *
     * Reading happens on a daemon thread so a malformed file cannot hang the
     * caller: ffprobe occasionally stalls on truncated media, and a scanner
     * that never finishes is worse than one that skips a file. The process is
     * killed on timeout.
     */
    private fun runBinary(command: List<String>, maxBytes: Int): ByteArray? {
        val process = try {
            ProcessBuilder(command).apply { redirectErrorStream(false) }.start()
        } catch (_: Exception) {
            return null
        }

        var captured: ByteArray? = null
        val reader = Thread({
            captured = try {
                process.inputStream.use { it.readAtMost(maxBytes) }
            } catch (_: Exception) {
                null
            }
        }, "ffprobe-reader")
        reader.isDaemon = true
        reader.start()

        // Drain stderr as well, or a chatty failure can fill the pipe and
        // deadlock the child before it ever exits.
        val errorDrain = Thread({
            runCatching { process.errorStream.use { it.readBytes() } }
        }, "ffprobe-stderr")
        errorDrain.isDaemon = true
        errorDrain.start()

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            reader.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return null
        }

        return if (process.exitValue() == 0) captured else null
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val sink = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (sink.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - sink.size()))
            if (read < 0) break
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val MAX_PROBE_BYTES = 4 * 1024 * 1024
        const val MAX_ART_BYTES = 24 * 1024 * 1024
    }
}
