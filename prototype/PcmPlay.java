import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Throwaway prototype for the FFmpeg decode pipeline. Not part of the app.
 *
 * Validates the four things the whole player design depends on:
 *   1. decoding arbitrary formats into raw PCM on stdout,
 *   2. how fast -ss seek actually is, and whether it lands where it should,
 *   3. what happens to the ffmpeg process while we stop draining the pipe (pause),
 *   4. whether process teardown leaves orphans behind.
 *
 * Run with: java prototype/PcmPlay.java <mode> [args]
 */
public final class PcmPlay {

    static final int SAMPLE_RATE = 44100;
    static final int CHANNELS = 2;
    static final int BYTES_PER_FRAME = CHANNELS * 2; // s16le
    static final int CHUNK = 8192;                   // multiple of BYTES_PER_FRAME

    static String ffmpegPath() {
        String override = System.getenv("FFMPEG_BIN");
        if (override != null && !override.isBlank()) return override;
        return Paths.get("third_party", "ffmpeg", "windows-x64", "ffmpeg.exe").toString();
    }

    static List<String> decodeCommand(String file, double seekSeconds) {
        Path exe = Paths.get(ffmpegPath()).toAbsolutePath();
        List<String> cmd = new ArrayList<>(List.of(
                exe.toString(),
                "-hide_banner", "-loglevel", "error", "-nostdin"));
        if (seekSeconds > 0) {
            // -ss BEFORE -i is the fast path: it seeks by index/byte offset
            // instead of decoding and discarding from the start.
            cmd.add("-ss");
            cmd.add(String.format(Locale.ROOT, "%.3f", seekSeconds));
        }
        cmd.add("-i");
        cmd.add(file);
        cmd.add("-map");
        cmd.add("0:a:0");
        cmd.add("-vn");
        cmd.add("-f");
        cmd.add("s16le");
        cmd.add("-ac");
        cmd.add(String.valueOf(CHANNELS));
        cmd.add("-ar");
        cmd.add(String.valueOf(SAMPLE_RATE));
        cmd.add("-");
        return cmd;
    }

    static Process start(String file, double seekSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(decodeCommand(file, seekSeconds));
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /** Drain stderr on a daemon thread so ffmpeg can never block on a full error pipe. */
    static Thread drainErrors(Process p, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (InputStream err = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) != -1) {
                    if (sink.length() < 8192) sink.append(new String(buf, 0, n));
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static long probeDurationMillis(String file) throws Exception {
        Path ffprobe = Paths.get(ffmpegPath()).resolveSibling(
                ffmpegPath().endsWith(".exe") ? "ffprobe.exe" : "ffprobe");
        Process p = new ProcessBuilder(
                ffprobe.toAbsolutePath().toString(),
                "-v", "error", "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1", file).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        double seconds = Double.parseDouble(out);
        return Math.round(seconds * 1000);
    }

    // ---------------------------------------------------------------- modes

    /** Fast-drain the pipe and report startup latency plus decoded audio length. */
    static int probe(String file, double seekSeconds) throws Exception {
        long t0 = System.nanoTime();
        Process p = start(file, seekSeconds);
        StringBuilder err = new StringBuilder();
        drainErrors(p, err);

        long firstByteNanos = -1;
        long total = 0;
        byte[] buf = new byte[CHUNK];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (firstByteNanos < 0) firstByteNanos = System.nanoTime();
                total += n;
            }
        }
        int code = p.waitFor();

        double firstByteMs = (firstByteNanos - t0) / 1e6;
        double totalMs = (System.nanoTime() - t0) / 1e6;
        double decodedSeconds = (double) total / (SAMPLE_RATE * BYTES_PER_FRAME);

        long expected = probeDurationMillis(file);
        double expectedSeconds = (expected / 1000.0) - seekSeconds;

        System.out.printf(Locale.ROOT,
                "  seek=%7.3fs  first-byte=%7.1fms  drain=%8.1fms  decoded=%7.3fs  expected=%7.3fs  delta=%+7.3fs  exit=%d%n",
                seekSeconds, firstByteMs, totalMs, decodedSeconds, expectedSeconds,
                decodedSeconds - expectedSeconds, code);
        if (err.length() > 0) {
            System.out.println("  stderr: " + err.toString().replace("\n", " | ").trim());
        }
        if (total == 0) System.out.println("  !! no PCM produced");
        return code;
    }

    /** Real audible playback through SourceDataLine. */
    static void play(String file, double seconds, double seek) throws Exception {
        Process p = start(file, seek);
        StringBuilder err = new StringBuilder();
        drainErrors(p, err);

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            System.out.println("line NOT supported: " + info);
            p.destroyForcibly();
            return;
        }
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            line.open(format, SAMPLE_RATE * BYTES_PER_FRAME / 2); // ~500ms buffer
            line.start();

            long frames = 0;
            byte[] buf = new byte[CHUNK];
            long deadline = seconds > 0 ? System.nanoTime() + (long) (seconds * 1e9) : Long.MAX_VALUE;
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    line.write(buf, 0, n);
                    frames += (long) n / BYTES_PER_FRAME;
                    if (System.nanoTime() > deadline) break;
                }
            }
            line.drain();
            System.out.printf(Locale.ROOT, "  played %.2fs of audio%n",
                    frames / (double) SAMPLE_RATE);
        }
        p.destroyForcibly();
        p.waitFor();
    }

    /** Pause = simply stop draining; ffmpeg should block on a full pipe, not spin. */
    static void pause(String file) throws Exception {
        Process p = start(file, 0);
        StringBuilder err = new StringBuilder();
        drainErrors(p, err);

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                new DataLine.Info(SourceDataLine.class, format))) {
            line.open(format, SAMPLE_RATE * BYTES_PER_FRAME / 2);
            line.start();
            byte[] buf = new byte[CHUNK];

            try (InputStream in = p.getInputStream()) {
                System.out.println("  reading for 1s...");
                long until = System.nanoTime() + 1_000_000_000L;
                while (System.nanoTime() < until) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    line.write(buf, 0, n);
                }
                line.flush();

                System.out.println("  pausing 3s (not draining the pipe)...");
                long cpuBefore = processCpuMillis(p.pid());
                Thread.sleep(3000);
                long cpuAfter = processCpuMillis(p.pid());
                System.out.printf("  during pause: alive=%s  ffmpeg cpu=%dms%n",
                        p.isAlive(), cpuAfter - cpuBefore);

                System.out.println("  resuming 1s...");
                until = System.nanoTime() + 1_000_000_000L;
                long bytes = 0;
                while (System.nanoTime() < until) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    bytes += n;
                    line.write(buf, 0, n);
                }
                System.out.println("  resumed, got " + bytes + " bytes back");
            }
        }
        p.destroyForcibly();
        p.waitFor();
    }

    /** Repeatedly kill and respawn; orphans show up in the surrounding shell check. */
    static void recycle(String file, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            Process p = start(file, i * 5.0);
            StringBuilder err = new StringBuilder();
            drainErrors(p, err);
            byte[] buf = new byte[CHUNK];
            int read = 0;
            try (InputStream in = p.getInputStream()) {
                read = in.read(buf);
            }
            long t0 = System.nanoTime();
            p.destroyForcibly();
            boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("  cycle %d: firstRead=%d bytes  kill->exit=%.1fms  exited=%s%n",
                    i + 1, read, ms, exited);
        }
    }

    static long processCpuMillis(long pid) {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue).CPU").start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (s.isEmpty()) return -1;
            return (long) (Double.parseDouble(s) * 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: java PcmPlay.java <probe|play|pause|recycle> <file> [arg]");
            return;
        }
        String mode = args[0];
        String file = args.length > 1 ? args[1] : "";
        System.out.println("ffmpeg: " + Paths.get(ffmpegPath()).toAbsolutePath());
        switch (mode) {
            case "probe" -> {
                double seek = args.length > 2 ? Double.parseDouble(args[2]) : 0;
                probe(file, seek);
            }
            case "seeks" -> {
                for (double s : new double[]{0, 5, 30, 60, 120}) {
                    probe(file, s);
                }
            }
            case "play" -> {
                double secs = args.length > 2 ? Double.parseDouble(args[2]) : 5;
                double seek = args.length > 3 ? Double.parseDouble(args[3]) : 0;
                play(file, secs, seek);
            }
            case "pause" -> pause(file);
            case "recycle" -> recycle(file, args.length > 2 ? Integer.parseInt(args[2]) : 5);
            default -> System.out.println("unknown mode: " + mode);
        }
    }

    private PcmPlay() {
    }
}
