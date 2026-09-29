import java.awt.Robot;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Parks the pointer over a point and holds it there, so hover-only UI such as
 * tooltips can be captured. A click would dismiss the very thing being
 * photographed.
 *
 * Usage: java Hover.java &lt;x&gt; &lt;y&gt; [settleSeconds]
 */
public final class Hover {
    public static void main(String[] args) throws Exception {
        int x = Integer.parseInt(args[0]);
        int y = Integer.parseInt(args[1]);
        double settle = args.length > 2 ? Double.parseDouble(args[2]) : 1.5;

        Robot robot = new Robot();
        // Approach from a distance so the pointer genuinely enters the target,
        // rather than starting on it and never producing an enter event.
        robot.mouseMove(x - 260, y + 160);
        Thread.sleep(300);
        robot.mouseMove(x, y);
        Thread.sleep((long) (settle * 1000));
        System.out.println("hovering " + x + "," + y + " for " + settle + "s");
    }

    private Hover() {
    }
}
