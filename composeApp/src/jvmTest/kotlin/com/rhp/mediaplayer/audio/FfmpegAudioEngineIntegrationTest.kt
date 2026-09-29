package com.rhp.mediaplayer.audio

import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.player.AudioEngineListener
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the real decode path: a real ffmpeg subprocess, real PCM on a pipe,
 * a real output line.
 *
 * Volume is pinned to zero, so the pipeline runs at full speed and the suite
 * stays silent -- the timing behaviour under test is unaffected by attenuation.
 *
 * The whole class skips itself when the environment cannot support it (no
 * ffmpeg staged, no audio device, or nothing that can synthesise the fixtures),
 * because a missing sound card is not a defect in this code.
 */
class FfmpegAudioEngineIntegrationTest {

    private val binaries: FfmpegBinaries? = FfmpegBinaries.locate()

    private fun haveOutputDevice(): Boolean {
        val format = AudioFormat(
            FfmpegAudioEngine.SAMPLE_RATE.toFloat(),
            16,
            FfmpegAudioEngine.CHANNELS,
            true,
            false,
        )
        return AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
    }

    /**
     * The decoder under test and the binary that synthesises its fixtures.
     *
     * These are not always the same file. The shipped decoder is deliberately
     * minimal -- it decodes and nothing else -- so it carries no `lavfi` input
     * and no encoders, and cannot produce the tones this suite then decodes.
     * Fixture generation falls back to whatever ffmpeg is on `PATH`; the decode
     * assertions still run against the staged decoder, which is the thing that
     * actually ships.
     */
    private class Environment(val decoder: FfmpegBinaries, val generator: Path)

    private fun requireEnvironment(): Environment? {
        val decoder = binaries
        if (decoder == null) {
            println("SKIP: ffmpeg not staged")
            return null
        }
        if (!haveOutputDevice()) {
            println("SKIP: no audio output device")
            return null
        }
        val generator = fixtureGenerator(decoder)
        if (generator == null) {
            println(
                "SKIP: nothing here can synthesise fixtures -- the staged build has no " +
                    "lavfi input or encoders, and PATH has no ffmpeg that does",
            )
            return null
        }
        return Environment(decoder, generator)
    }

    /**
     * The staged decoder when it can synthesise, otherwise any ffmpeg on `PATH`
     * that can. A generator that lacks one of the codecs would fail the suite
     * rather than skip it, so the probe asks for the whole set.
     */
    private fun fixtureGenerator(decoder: FfmpegBinaries): Path? {
        if (canSynthesise(decoder.ffmpeg)) return decoder.ffmpeg

        for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = Paths.get(dir, "ffmpeg")
            if (Files.isExecutable(candidate) && canSynthesise(candidate)) return candidate
        }
        return null
    }

    /** Cheap and honest: encodes one second of sine in every form the suite generates. */
    private fun canSynthesise(ffmpeg: Path): Boolean {
        val directory = try {
            Files.createTempDirectory("cmp-fixture-probe")
        } catch (_: Exception) {
            return false
        }
        try {
            // The WAV case is probed too, and is not in FIXTURE_CASES: it is the
            // one form the decoder test generates outside that table.
            for ((name, codecArgs) in FIXTURE_CASES + ("probe.wav" to listOf("-c:a", "pcm_s16le"))) {
                val target = directory.resolve(name)
                val process = ProcessBuilder(
                    buildList {
                        add(ffmpeg.toString())
                        addAll(listOf("-hide_banner", "-loglevel", "error", "-y"))
                        addAll(listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1"))
                        addAll(codecArgs)
                        add(target.toString())
                    },
                ).redirectErrorStream(true).start()
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return false
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Generates a sine of [seconds] in the given container/codec. */
    private fun generate(
        ffmpeg: Path,
        directory: Path,
        name: String,
        seconds: Int,
        codecArgs: List<String>,
    ): Song {
        val target = directory.resolve(name)
        val command = buildList {
            add(ffmpeg.toString())
            addAll(listOf("-hide_banner", "-loglevel", "error", "-y"))
            addAll(listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=$seconds"))
            addAll(codecArgs)
            add(target.toString())
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg failed to generate $name: $output" }
        return Song(id = target.toString(), path = target.toString(), title = name)
    }

    private class Recorder : AudioEngineListener {
        val completions = AtomicInteger(0)
        val error = AtomicReference<String?>(null)
        val completed = CountDownLatch(1)

        override fun onTrackCompleted() {
            completions.incrementAndGet()
            completed.countDown()
        }

        override fun onPlaybackError(message: String) {
            error.compareAndSet(null, message)
            completed.countDown()
        }
    }

    private fun awaitPosition(engine: FfmpegAudioEngine, atLeastMs: Long, timeoutMs: Long = 5000): Long {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val position = engine.positionMs
            if (position >= atLeastMs) return position
            Thread.sleep(20)
        }
        return engine.positionMs
    }

    @Test
    fun `plays a wav through to completion`() {
        val environment = requireEnvironment() ?: return
        val dir = Files.createTempDirectory("cmp-engine")
        val song = generate(environment.generator, dir, "tone.wav", seconds = 3, codecArgs = listOf("-c:a", "pcm_s16le"))

        val engine = FfmpegAudioEngine(environment.decoder)
        val recorder = Recorder()
        engine.listener = recorder
        engine.setVolume(0f)
        try {
            engine.play(song)

            assertTrue(
                recorder.completed.await(20, TimeUnit.SECONDS),
                "playback never completed (error=${recorder.error.get()})",
            )
            assertTrue(
                recorder.error.get() == null,
                "unexpected playback error: ${recorder.error.get()}",
            )
            assertTrue(
                recorder.completions.get() == 1,
                "completion should fire exactly once, fired ${recorder.completions.get()}",
            )
            assertTrue(
                engine.positionMs >= 2500,
                "position should have reached the end, was ${engine.positionMs}",
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun `decodes mp3 flac and m4a alike`() {
        val environment = requireEnvironment() ?: return
        val dir = Files.createTempDirectory("cmp-engine-formats")

        for ((name, codec) in FIXTURE_CASES) {
            val song = generate(environment.generator, dir, name, seconds = 2, codecArgs = codec)
            val engine = FfmpegAudioEngine(environment.decoder)
            val recorder = Recorder()
            engine.listener = recorder
            engine.setVolume(0f)
            try {
                engine.play(song)
                assertTrue(
                    recorder.completed.await(20, TimeUnit.SECONDS),
                    "$name did not finish (error=${recorder.error.get()})",
                )
                assertTrue(recorder.error.get() == null, "$name failed: ${recorder.error.get()}")
                assertTrue(recorder.completions.get() == 1, "$name fired ${recorder.completions.get()} completions")
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun `pause freezes the clock and resume continues it`() {
        val environment = requireEnvironment() ?: return
        val dir = Files.createTempDirectory("cmp-engine-pause")
        val song = generate(environment.generator, dir, "long.wav", seconds = 10, codecArgs = listOf("-c:a", "pcm_s16le"))

        val engine = FfmpegAudioEngine(environment.decoder)
        val recorder = Recorder()
        engine.listener = recorder
        engine.setVolume(0f)
        try {
            engine.play(song)
            awaitPosition(engine, 400)

            engine.pause()
            // Let the line drain whatever it had buffered before sampling.
            Thread.sleep(300)
            val frozen = engine.positionMs
            Thread.sleep(700)
            val stillFrozen = engine.positionMs

            assertTrue(
                stillFrozen - frozen <= 120,
                "position moved while paused: $frozen -> $stillFrozen",
            )

            engine.resume()
            val afterResume = awaitPosition(engine, stillFrozen + 300)
            assertTrue(
                afterResume > stillFrozen,
                "position did not advance after resume: $stillFrozen -> $afterResume",
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun `seek lands at the requested offset`() {
        val environment = requireEnvironment() ?: return
        val dir = Files.createTempDirectory("cmp-engine-seek")
        val song = generate(environment.generator, dir, "long.wav", seconds = 30, codecArgs = listOf("-c:a", "pcm_s16le"))

        val engine = FfmpegAudioEngine(environment.decoder)
        val recorder = Recorder()
        engine.listener = recorder
        engine.setVolume(0f)
        try {
            engine.play(song)
            awaitPosition(engine, 300)

            engine.seekTo(20_000)

            val reached = awaitPosition(engine, 20_000, timeoutMs = 5000)
            assertTrue(
                reached >= 20_000 && reached < 22_500,
                "seek should land near 20s, position was $reached",
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun `stopping mid-track does not report completion`() {
        val environment = requireEnvironment() ?: return
        val dir = Files.createTempDirectory("cmp-engine-stop")
        val song = generate(environment.generator, dir, "long.wav", seconds = 20, codecArgs = listOf("-c:a", "pcm_s16le"))

        val engine = FfmpegAudioEngine(environment.decoder)
        val recorder = Recorder()
        engine.listener = recorder
        engine.setVolume(0f)
        try {
            engine.play(song)
            awaitPosition(engine, 300)
            engine.stop()

            // Killing the decoder makes its read return -1, which looks exactly
            // like a track ending. The engine must not confuse the two.
            Thread.sleep(1200)
            assertTrue(
                recorder.completions.get() == 0,
                "a stopped track must not report completion",
            )
            assertNotNull(engine)
        } finally {
            engine.release()
        }
    }

    @Test
    fun `an unreadable file reports an error instead of silence`() {
        val environment = requireEnvironment() ?: return
        val dir = Files.createTempDirectory("cmp-engine-bad")
        val broken = dir.resolve("broken.mp3")
        Files.write(broken, ByteArray(512) { 0x2A })
        val song = Song(id = broken.toString(), path = broken.toString(), title = "broken")

        val engine = FfmpegAudioEngine(environment.decoder)
        val recorder = Recorder()
        engine.listener = recorder
        engine.setVolume(0f)
        try {
            engine.play(song)
            assertTrue(
                recorder.completed.await(15, TimeUnit.SECONDS),
                "a broken file should surface something",
            )
            assertNotNull(recorder.error.get(), "a broken file should report an error")
        } finally {
            engine.release()
        }
    }

    private companion object {
        /**
         * The containers and codecs the decode test covers, and therefore also
         * the set a fixture generator has to be able to produce.
         */
        val FIXTURE_CASES = mapOf(
            "tone.mp3" to listOf("-c:a", "libmp3lame", "-b:a", "192k"),
            "tone-vbr.mp3" to listOf("-c:a", "libmp3lame", "-q:a", "4"),
            "tone.flac" to listOf("-c:a", "flac"),
            "tone.m4a" to listOf("-c:a", "aac", "-b:a", "192k"),
        )
    }
}
