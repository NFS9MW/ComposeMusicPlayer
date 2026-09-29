import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Presses one named key, so keyboard handling can be exercised without a hand on
 * the keyboard. Only the keys this app actually reacts to are mapped; anything
 * else would be guesswork about what a name means.
 *
 * Usage: java Key.java &lt;name&gt; [delayMillisAfter]
 *   names: escape, enter, space, left, right, tab, backspace
 */
public final class Key {
    public static void main(String[] args) throws Exception {
        int code = codeFor(args[0].toLowerCase());
        int after = args.length > 1 ? Integer.parseInt(args[1]) : 900;

        Robot robot = new Robot();
        robot.setAutoDelay(40);
        robot.keyPress(code);
        robot.keyRelease(code);
        Thread.sleep(after);
        System.out.println("pressed " + args[0] + " (" + code + ")");
    }

    private static int codeFor(String name) {
        switch (name) {
            case "escape":
            case "esc":
                return KeyEvent.VK_ESCAPE;
            case "enter":
            case "return":
                return KeyEvent.VK_ENTER;
            case "space":
                return KeyEvent.VK_SPACE;
            case "left":
                return KeyEvent.VK_LEFT;
            case "right":
                return KeyEvent.VK_RIGHT;
            case "tab":
                return KeyEvent.VK_TAB;
            case "backspace":
                return KeyEvent.VK_BACK_SPACE;
            default:
                throw new IllegalArgumentException("unmapped key: " + name);
        }
    }

    private Key() {
    }
}
