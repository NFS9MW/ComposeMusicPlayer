import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Drives the running app with synthetic input so behaviour that only exists in
 * the interaction -- text entry, and losing focus when clicking away -- can be
 * checked without a human at the keyboard. Screenshots are captured before and
 * after so the two states can be compared.
 *
 * Usage: java InteractionTest.java <outputDir> [searchFieldX] [searchFieldY] [blankX] [blankY]
 */
public final class InteractionTest {

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "interaction");
        Files.createDirectories(outDir);

        int fieldX = args.length > 1 ? Integer.parseInt(args[1]) : 720;
        int fieldY = args.length > 2 ? Integer.parseInt(args[2]) : 120;
        int blankX = args.length > 3 ? Integer.parseInt(args[3]) : 150;
        int blankY = args.length > 4 ? Integer.parseInt(args[4]) : 500;

        Robot robot = new Robot();
        robot.setAutoDelay(70);
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

        // Focus the search field and type into it.
        click(robot, fieldX, fieldY);
        Thread.sleep(500);
        for (char c : "filler".toCharArray()) {
            typeChar(robot, c);
        }
        Thread.sleep(800);
        capture(robot, screen, outDir.resolve("a-typed.png"));
        System.out.println("typed into " + fieldX + "," + fieldY);

        // Click empty space elsewhere -- this is the behaviour under test.
        click(robot, blankX, blankY);
        Thread.sleep(800);
        capture(robot, screen, outDir.resolve("b-clicked-away.png"));
        System.out.println("clicked away at " + blankX + "," + blankY);

        System.out.println("done");
    }

    private static void click(Robot robot, int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void typeChar(Robot robot, char c) {
        int code = KeyEvent.getExtendedKeyCodeForChar(c);
        robot.keyPress(code);
        robot.keyRelease(code);
    }

    private static void capture(Robot robot, Rectangle screen, Path target) throws Exception {
        BufferedImage image = robot.createScreenCapture(screen);
        ImageIO.write(image, "png", target.toFile());
    }

    private InteractionTest() {
    }
}
