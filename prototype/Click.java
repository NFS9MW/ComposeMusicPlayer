import java.awt.Robot;
import java.awt.event.InputEvent;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Clicks one point, so UI that only responds to a real press can be exercised
 * without a hand on the mouse. Coordinates are in the screen space a Java
 * screenshot uses, which is how a 1:1 crop of one of those screenshots can be
 * used to find the target.
 *
 * Usage: java Click.java &lt;x&gt; &lt;y&gt; [delaySecondsBefore] [delayMillisAfter]
 */
public final class Click {
    public static void main(String[] args) throws Exception {
        int x = Integer.parseInt(args[0]);
        int y = Integer.parseInt(args[1]);
        double before = args.length > 2 ? Double.parseDouble(args[2]) : 0.0;
        int after = args.length > 3 ? Integer.parseInt(args[3]) : 900;

        if (before > 0) {
            Thread.sleep((long) (before * 1000));
        }

        Robot robot = new Robot();
        robot.setAutoDelay(60);
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(after);
        System.out.println("clicked " + x + "," + y);
    }

    private Click() {
    }
}
