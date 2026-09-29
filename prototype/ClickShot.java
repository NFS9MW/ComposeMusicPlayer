import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

/**
 * Scrolls, clicks, and captures -- all in one process, so the delay between the
 * click and the screenshot is the delay that was asked for.
 *
 * The existing tools each start their own JVM, which costs about half a second
 * before anything happens. That is fine for "click, then look", and useless for
 * anything that has to be caught mid-animation: a 1100 ms flash is over before
 * the second JVM has finished booting.
 *
 * Usage:
 *   java ClickShot.java &lt;out.png&gt; [--wheel x y notches] [--click x y]
 *                                  [--before ms] [--after ms]
 *
 * Order of operations: wait --before, scroll if asked, wait a moment for the
 * scroll to settle, click if asked, wait --after, capture. The scroll settle
 * delay is skipped when there is no click, so a plain "scroll and look" is quick.
 */
public final class ClickShot {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java ClickShot.java <out.png> "
                    + "[--wheel x y notches] [--click x y] [--before ms] [--after ms]");
            System.exit(2);
        }

        Path out = Paths.get(args[0]);
        Integer wheelX = null;
        Integer wheelY = null;
        int notches = 0;
        Integer clickX = null;
        Integer clickY = null;
        int before = 200;
        int after = 150;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--wheel" -> {
                    wheelX = Integer.parseInt(args[++i]);
                    wheelY = Integer.parseInt(args[++i]);
                    notches = Integer.parseInt(args[++i]);
                }
                case "--click" -> {
                    clickX = Integer.parseInt(args[++i]);
                    clickY = Integer.parseInt(args[++i]);
                }
                case "--before" -> before = Integer.parseInt(args[++i]);
                case "--after" -> after = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        Robot robot = new Robot();
        // The window has to have the focus for a key or a scroll to reach it.
        // The caller is expected to have run WindowDriver focus first.

        sleep(before);

        if (wheelX != null) {
            robot.mouseMove(wheelX, wheelY);
            sleep(120);
            robot.mouseWheel(notches);
            // The list is skippable and its scroll is momentum-free, but a
            // gesture still needs a frame or two to land before it is captured.
            sleep(clickX == null ? after : 500);
        }

        if (clickX != null) {
            robot.mouseMove(clickX, clickY);
            sleep(80);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            sleep(30);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            sleep(after);
        }

        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage image = robot.createScreenCapture(screen);
        File parent = out.toAbsolutePath().getParent().toFile();
        if (parent.isDirectory() || parent.mkdirs()) {
            ImageIO.write(image, "png", out.toFile());
            System.out.println("saved " + out.toAbsolutePath()
                    + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        } else {
            System.err.println("cannot write to " + parent);
            System.exit(1);
        }
    }

    private static void sleep(int millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
