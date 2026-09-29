import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Captures the desktop to a PNG so the UI can be inspected without a human at
 * the keyboard.
 *
 * Usage: java Shot.java <output.png> [delaySeconds]
 */
public final class Shot {
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "shot.png");
        int delay = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        if (delay > 0) {
            Thread.sleep(delay * 1000L);
        }

        Robot robot = new Robot();
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage image = robot.createScreenCapture(screen);
        ImageIO.write(image, "png", out.toFile());
        System.out.println("saved " + out.toAbsolutePath() + " (" + image.getWidth() + "x" + image.getHeight() + ")");
    }

    private Shot() {
    }
}
