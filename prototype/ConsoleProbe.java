import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Decides empirically whether spawning ffmpeg from a console-less (javaw) JVM
 * puts a console window on screen. A conhost.exe child only proves a console
 * was allocated -- CREATE_NO_WINDOW allocates one too, just without a window.
 * So the only trustworthy evidence is what the desktop actually looks like.
 *
 * Takes a screenshot before and after the spawn and reports how many pixels
 * changed, then saves both frames for visual confirmation.
 *
 * Run with javaw.
 */
public final class ConsoleProbe {

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args.length > 0 ? args[0] : ".workbuddy/cache/consoleshot");
        Files.createDirectories(outDir);

        String ffmpeg = System.getenv().getOrDefault("FFMPEG_BIN",
                "third_party/ffmpeg/windows-x64/ffmpeg.exe");
        Path exe = Paths.get(ffmpeg).toAbsolutePath();

        Robot robot = new Robot();
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

        Thread.sleep(1500);
        BufferedImage before = robot.createScreenCapture(screen);
        ImageIO.write(before, "png", outDir.resolve("before.png").toFile());

        // -re makes lavfi produce at real time, so ffmpeg stays alive 20s
        // instead of finishing the whole sine in a couple of seconds.
        ProcessBuilder pb = new ProcessBuilder(
                exe.toString(),
                "-hide_banner", "-loglevel", "error", "-nostdin",
                "-re", "-f", "lavfi", "-i", "sine=frequency=440:duration=20",
                "-f", "s16le", "-ar", "44100", "-ac", "2", "-");
        Process p = pb.start();

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

        Thread.sleep(3000);
        BufferedImage during = robot.createScreenCapture(screen);
        ImageIO.write(during, "png", outDir.resolve("during.png").toFile());

        long changed = 0;
        for (int y = 0; y < before.getHeight(); y += 2) {
            for (int x = 0; x < before.getWidth(); x += 2) {
                if (before.getRGB(x, y) != during.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        long sampled = (long) (before.getHeight() / 2) * (before.getWidth() / 2);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(outDir.resolve("result.txt")))) {
            w.println("screen=" + screen.width + "x" + screen.height);
            w.println("changedSamples=" + changed + " of " + sampled
                    + String.format(" (%.4f%%)", 100.0 * changed / sampled));
            w.println("childAlive=" + p.isAlive());
        }

        p.destroyForcibly();
        p.waitFor();
    }

    private ConsoleProbe() {
    }
}
