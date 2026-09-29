import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Windows allocates a console window for a console-subsystem child process
 * unless the parent passes CREATE_NO_WINDOW. Whether that bites us depends on
 * the JDK's own ProcessBuilder behaviour, and it only manifests when the JVM
 * itself has no console (i.e. when launched via javaw, which is exactly how a
 * jpackage-built GUI app runs).
 *
 * Spawns a long-lived ffmpeg and holds it, so an external check can inspect
 * whether the child owns a console window. Results go to a file because javaw
 * has nowhere to print.
 *
 * Run with: javaw -cp <classes> SpawnTest <outputFile> <holdSeconds>
 */
public final class SpawnTest {

    static String ffmpegPath() {
        String override = System.getenv("FFMPEG_BIN");
        if (override != null && !override.isBlank()) return override;
        return Paths.get("third_party", "ffmpeg", "windows-x64", "ffmpeg.exe").toString();
    }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "spawntest-result.txt");
        int holdSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 15;

        Path exe = Paths.get(ffmpegPath()).toAbsolutePath();
        String jvmName = ProcessHandle.current().info().command()
                .map(c -> Paths.get(c).getFileName().toString()).orElse("?");

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("jvm=" + jvmName + " pid=" + ProcessHandle.current().pid());
            w.println("ffmpeg=" + exe);
            w.flush();

            ProcessBuilder pb = new ProcessBuilder(
                    exe.toString(),
                    "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=600",
                    "-f", "s16le", "-ar", "44100", "-ac", "2", "-");
            Process p = pb.start();
            w.println("childPid=" + p.pid());
            w.println("childAlive=" + p.isAlive());
            w.println("state=HOLDING");
            w.flush();

            // Keep the pipe drained so ffmpeg never blocks; we only care about
            // the console window, not the audio.
            Thread drain = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[8192];
                    while (in.read(buf) != -1) {
                        // discard
                    }
                } catch (IOException ignored) {
                }
            }, "drain");
            drain.setDaemon(true);
            drain.start();

            w.println("descendants=" + ProcessHandle.current().descendants()
                    .map(h -> h.info().command().orElse("?") + "#" + h.pid())
                    .toList());
            w.flush();

            Thread.sleep(holdSeconds * 1000L);

            w.println("stillAlive=" + p.isAlive());
            p.destroyForcibly();
            boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            w.println("killExited=" + exited);
            Thread.sleep(1000);
            List<ProcessHandle> left = ProcessHandle.current().descendants().toList();
            w.println("descendantsAfterKill=" + left.size());
            w.println("state=DONE");
        }
    }

    private SpawnTest() {
    }
}
