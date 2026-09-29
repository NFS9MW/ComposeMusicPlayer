package com.rhp.mediaplayer.audio

import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.player.AudioEngine
import com.rhp.mediaplayer.player.AudioEngineListener
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Plays audio by piping decoded PCM out of an ffmpeg subprocess.
 *
 * ffmpeg does the decoding and nothing else; the JVM still owns the output line.
 * That split is what makes seeking trivial: ffmpeg's `-ss` is accurate and
 * format-agnostic, so a seek costs one process restart (~150 ms) instead of the
 * hand-rolled per-codec index arithmetic a pure-Java decoder would need.
 *
 * Three things here are less obvious than they look:
 *
 *  * **Writes are bounded by [SourceDataLine.available].** Blocking in
 *    `write` while the line is stopped would deadlock: the pump would be stuck
 *    inside `write`, never reaching the pause check, and pause could never be
 *    observed. Writing only what fits keeps pause, seek and stop responsive.
 *  * **Reads are not frame-aligned.** A pipe hands back arbitrary byte counts
 *    (4652 and 7300 have both been observed), and `write` rejects a length that
 *    is not a whole number of frames. The leftover bytes are carried over.
 *  * **Completion is generation-checked.** Stopping or seeking kills the pump's
 *    process, which makes its read return -1 -- indistinguishable from a track
 *    ending naturally. A stale pump must not be allowed to advance the queue.
 */
class FfmpegAudioEngine(
    private val binaries: FfmpegBinaries,
    private val onStderr: (String) -> Unit = {},
) : AudioEngine {

    override var listener: AudioEngineListener? = null

    @Volatile
    private var pump: Thread? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var output: SourceDataLine? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var framesWritten = 0L

    @Volatile
    private var startOffsetMs = 0L

    @Volatile
    private var volume = 1f

    @Volatile
    private var currentSong: Song? = null

    /** Bumped on every start so a superseded pump stays silent. */
    private val generation = AtomicLong(0)

    private val pauseLock = Object()

    // ------------------------------------------------------------ playback

    override val isPlaying: Boolean get() = running && !paused

    override val positionMs: Long
        get() {
            val line = output ?: return startOffsetMs
            // Frames handed to the line run ahead of what is audible by whatever
            // is still buffered, so subtract that or the clock jumps ahead at
            // every seek and pause.
            val buffered = (line.bufferSize - line.available()).coerceAtLeast(0)
            val audibleFrames = (framesWritten - buffered / BYTES_PER_FRAME).coerceAtLeast(0)
            return startOffsetMs + audibleFrames * 1000L / SAMPLE_RATE
        }

    override fun play(song: Song, startMs: Long) {
        startPlayback(song, startMs, startPaused = false)
    }

    override fun pause() {
        if (!running || paused) return
        // Flag first so the pump parks before the line goes quiet, otherwise it
        // can slip one more write into the buffer after the user hit pause.
        paused = true
        line()?.stop()
    }

    override fun resume() {
        if (!running || !paused) return
        paused = false
        line()?.start()
        wakePaused()
    }

    override fun stop() {
        haltPump()
        paused = false
        currentSong = null
        framesWritten = 0
        startOffsetMs = 0
        line()?.flush()
    }

    override fun seekTo(positionMs: Long) {
        val song = currentSong ?: return
        val target = positionMs.coerceAtLeast(0)
        // Seeking is just a restart at a new offset; keeping the pause state
        // means seeking while paused stays silent instead of blurting audio.
        startPlayback(song, target, startPaused = paused)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun prewarm() {
        Thread({
            runCatching {
                val warm = ProcessBuilder(binaries.ffmpeg.toString(), "-version")
                    .redirectErrorStream(true)
                    .start()
                warm.inputStream.use { it.readBytes() }
                warm.waitFor()
            }
        }, "ffmpeg-prewarm").apply { isDaemon = true }.start()
    }

    override fun release() {
        stop()
        runCatching { line()?.close() }
        output = null
        listener = null
    }

    // ----------------------------------------------------------- internals

    private fun line(): SourceDataLine? = output?.takeIf { it.isOpen }

    private fun startPlayback(song: Song, startMs: Long, startPaused: Boolean) {
        haltPump()

        currentSong = song
        startOffsetMs = startMs.coerceAtLeast(0)
        framesWritten = 0
        paused = startPaused
        running = true

        val myGeneration = generation.incrementAndGet()
        val thread = Thread({ runPump(song, startOffsetMs, myGeneration) }, "audio-pump")
        thread.isDaemon = true
        thread.priority = (Thread.NORM_PRIORITY + 2).coerceAtMost(Thread.MAX_PRIORITY)
        pump = thread
        thread.start()
    }

    private fun haltPump() {
        running = false
        wakePaused()
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null

        val thread = pump
        pump = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                // The pump only ever blocks briefly: it sleeps in 2 ms slices
                // rather than waiting on a full pipe.
                thread.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runPump(song: Song, startMs: Long, myGeneration: Long) {
        var child: Process? = null
        var problem: String? = null
        var reachedEnd = false
        val stderr = StringBuilder()

        try {
            val line = ensureLine()
            if (line == null) {
                problem = "找不到可用的音频输出设备"
            } else {
                child = ProcessBuilder(buildCommand(song, startMs)).start()
                process = child
                val stderrThread = drainErrors(child.errorStream, stderr)

                line.flush()
                line.start()
                reachedEnd = pumpAudio(child.inputStream, line)
                stderrThread.join(200)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            problem = e.message ?: "无法启动 ffmpeg"
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        } finally {
            child?.let { if (it.isAlive) it.destroyForcibly() }
            if (process === child) process = null
            runCatching { line()?.stop() }
        }

        // A superseded or halted pump must never advance the queue.
        if (myGeneration != generation.get() || !running) return

        val reported = when {
            problem != null -> problem
            !reachedEnd -> null
            framesWritten == 0L -> firstLine(stderr) ?: "无法解码该文件"
            else -> null
        }

        if (reported != null) {
            onStderr(stderr.toString())
            listener?.onPlaybackError(reported)
        } else {
            listener?.onTrackCompleted()
        }
    }

    /**
     * Feeds the output line until the track ends or playback is halted.
     *
     * Returns true when the decoder reported end-of-stream, false when the pump
     * was stopped or superseded.
     */
    private fun pumpAudio(input: InputStream, line: SourceDataLine): Boolean {
        val chunk = ByteArray(READ_CHUNK)
        var carry = 0

        while (running) {
            awaitUnpaused()
            if (!running) return false

            val read = input.read(chunk, carry, chunk.size - carry)
            if (read < 0) {
                // Let whatever is already buffered finish playing before the
                // track is declared over, or the last fraction of a second is
                // clipped off.
                drainLine(line)
                return running
            }
            if (read == 0) continue

            val filled = carry + read
            val usable = filled - (filled % BYTES_PER_FRAME)

            var offset = 0
            while (offset < usable) {
                awaitUnpaused()
                if (!running) return false

                val writable = (minOf(line.available(), usable - offset) / BYTES_PER_FRAME) *
                    BYTES_PER_FRAME
                if (writable <= 0) {
                    // Buffer is full: the line is draining at real time and
                    // this is the backpressure that keeps memory flat.
                    Thread.sleep(2)
                    continue
                }

                applyGain(chunk, offset, writable)
                line.write(chunk, offset, writable)
                offset += writable
                framesWritten += (writable / BYTES_PER_FRAME).toLong()
            }

            val remainder = filled - usable
            if (remainder > 0) {
                System.arraycopy(chunk, usable, chunk, 0, remainder)
            }
            carry = remainder
        }
        return false
    }

    private fun drainLine(line: SourceDataLine) {
        while (running) {
            if (line.bufferSize - line.available() <= 0) return
            awaitUnpaused()
            Thread.sleep(2)
        }
    }

    private fun awaitUnpaused() {
        if (!paused) return
        synchronized(pauseLock) {
            while (paused && running) {
                try {
                    pauseLock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun wakePaused() {
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    private fun ensureLine(): SourceDataLine? {
        line()?.let { return it }

        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) return null

        return try {
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format, LINE_BUFFER_BYTES)
                output = it
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Applies volume in software rather than through MASTER_GAIN.
     *
     * MASTER_GAIN is not exposed by every mixer -- notably it is patchy on
     * Linux -- whereas scaling the samples works everywhere, and it lets the
     * volume slider take effect mid-buffer instead of on the next track.
     */
    private fun applyGain(buffer: ByteArray, offset: Int, length: Int) {
        val gain = volume
        if (gain >= 0.999f) return

        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            val low = buffer[index].toInt() and 0xFF
            val high = buffer[index + 1].toInt()
            val sample = (high shl 8) or low

            val scaled = when {
                sample > 0 -> (sample * gain).toInt()
                else -> -((-sample * gain).toInt())
            }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            buffer[index] = scaled.toByte()
            buffer[index + 1] = (scaled shr 8).toByte()
            index += 2
        }
    }

    private fun buildCommand(song: Song, startMs: Long): List<String> {
        val command = mutableListOf(
            binaries.ffmpeg.toString(),
            "-hide_banner", "-loglevel", "error",
            // Without this ffmpeg may consume our stdin and stall.
            "-nostdin",
        )
        if (startMs > 0) {
            // -ss before -i is the fast path: it seeks by index or byte offset
            // rather than decoding and discarding from the beginning.
            command += listOf("-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0))
        }
        command += listOf(
            "-i", song.path,
            // First audio stream only; without -vn an embedded cover is treated
            // as a video stream and can derail the mapping.
            "-map", "0:a:0",
            "-vn",
            // s16le keeps the format fixed, so one output line serves every
            // track and volume can be applied with plain sample scaling.
            "-f", "s16le",
            "-ac", CHANNELS.toString(),
            "-ar", SAMPLE_RATE.toString(),
            "-",
        )
        return command
    }

    private fun drainErrors(stream: InputStream, sink: StringBuilder): Thread {
        val thread = Thread({
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (sink.length < 4096) sink.appendLine(line)
                    }
                }
            } catch (_: IOException) {
                // The stream closes when the process is killed; not an error.
            }
        }, "ffmpeg-stderr")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun firstLine(text: StringBuilder): String? =
        text.toString().lineSequence().firstOrNull { it.isNotBlank() }?.trim()

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2

        /** Multiple of the frame size, so a read never ends mid-frame by design. */
        private const val READ_CHUNK = 8192

        /** About 250 ms of slack; small enough that pause and seek feel instant. */
        private const val LINE_BUFFER_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 4
    }
}
