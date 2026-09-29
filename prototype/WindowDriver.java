import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Drives the app's window through Win32 so window placement can be verified
 * without a hand on the mouse. Asking Win32 directly is the point: IsZoomed and
 * IsIconic report what the operating system thinks the window is doing, which
 * is what the app has to agree with, rather than what the app happens to
 * remember.
 *
 * Usage: java -cp "prototype/out;prototype/lib/*" WindowDriver &lt;command&gt; [args]
 *   info                          print title, pid, rectangle and placement
 *   maximize | restore | minimize | close
 *   shot &lt;file.png&gt;               capture the window's rectangle
 *   wait &lt;seconds&gt;                sleep, for use between the commands above
 */
public final class WindowDriver {

    /** User32 as JNA declares it, plus the two queries it happens to omit. */
    public interface Desktop extends User32 {
        // W32APIOptions matters: without the Unicode mapping JNA looks for a
        // plain "FindWindow" export, and the DLL only has FindWindowW.
        Desktop INSTANCE = Native.load("user32", Desktop.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean IsZoomed(HWND hwnd);

        boolean IsIconic(HWND hwnd);

        boolean ShowWindow(HWND hwnd, int cmd);

        boolean SetForegroundWindow(HWND hwnd);
    }

    private static final Desktop DESKTOP = Desktop.INSTANCE;

    private static final int WM_SYSCOMMAND = 0x0112;
    private static final int WM_CLOSE = 0x0010;
    private static final int SC_MAXIMIZE = 0xF030;
    private static final int SC_MINIMIZE = 0xF020;
    private static final int SC_RESTORE = 0xF120;
    private static final int SW_MINIMIZE = 6;
    private static final int SW_RESTORE = 9;

    private static final String TITLE = "Compose Music Player";

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "info";

        if ("wait".equals(command)) {
            Thread.sleep((long) (Double.parseDouble(args[1]) * 1000));
            return;
        }

        HWND hwnd = findWindow();
        if (hwnd == null) {
            System.out.println("window not found: " + TITLE);
            System.exit(2);
        }

        switch (command) {
            case "info" -> printInfo(hwnd);
            case "maximize" -> send(hwnd, SC_MAXIMIZE);
            case "restore" -> send(hwnd, SC_RESTORE);
            case "minimize" -> send(hwnd, SC_MINIMIZE);
            case "close" -> DESKTOP.PostMessage(hwnd, WM_CLOSE, new WPARAM(0), new LPARAM(0));
            case "changeAndQuit" -> {
                int action = "maximize".equals(args[1]) ? SC_MAXIMIZE : SC_RESTORE;
                changeAndQuit(hwnd, action, (long) (Double.parseDouble(args[2]) * 1000));
            }
            case "focus" -> focus(hwnd);
            case "shot" -> capture(hwnd, Paths.get(args[1]));
            default -> throw new IllegalArgumentException("unknown command: " + command);
        }
    }

    private static HWND findWindow() {
        return DESKTOP.FindWindow(null, TITLE);
    }

    private static void send(HWND hwnd, int sysCommand) throws Exception {
        send(hwnd, sysCommand, 700);
    }

    private static void send(HWND hwnd, int sysCommand, long settleMs) throws Exception {
        DESKTOP.SendMessage(hwnd, WM_SYSCOMMAND, new WPARAM(sysCommand), new LPARAM(0));
        Thread.sleep(settleMs);
    }

    /**
     * Changes the placement and quits without waiting for the app's save
     * debounce, which is the case where a pending write would be dropped if
     * shutdown cancelled it instead of flushing.
     */
    private static void changeAndQuit(HWND hwnd, int sysCommand, long settleMs) throws Exception {
        send(hwnd, sysCommand, settleMs);
        DESKTOP.PostMessage(hwnd, WM_CLOSE, new WPARAM(0), new LPARAM(0));
    }

    private static void printInfo(HWND hwnd) {
        RECT rect = new RECT();
        DESKTOP.GetWindowRect(hwnd, rect);

        IntByReference pidOut = new IntByReference();
        DESKTOP.GetWindowThreadProcessId(hwnd, pidOut);

        System.out.println("hwnd       : " + hwnd);
        System.out.println("pid        : " + pidOut.getValue());
        System.out.println("isZoomed   : " + DESKTOP.IsZoomed(hwnd));
        System.out.println("isIconic   : " + DESKTOP.IsIconic(hwnd));
        System.out.println("rect       : " + rect.left + "," + rect.top + " "
                + (rect.right - rect.left) + "x" + (rect.bottom - rect.top));
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        System.out.println("screen     : " + screen.width + "x" + screen.height);
    }

    private static void focus(HWND hwnd) throws Exception {
        // SetForegroundWindow alone is routinely refused. Minimising and
        // restoring goes through the shell's own activation path, so it brings
        // the window forward even when the requesting process is not the
        // foreground one.
        DESKTOP.ShowWindow(hwnd, SW_MINIMIZE);
        Thread.sleep(500);
        DESKTOP.ShowWindow(hwnd, SW_RESTORE);
        Thread.sleep(500);
        DESKTOP.SetForegroundWindow(hwnd);
        Thread.sleep(800);
    }

    private static void capture(HWND hwnd, Path target) throws Exception {
        RECT rect = new RECT();
        DESKTOP.GetWindowRect(hwnd, rect);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Rectangle bounds = new Rectangle(
                rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
        BufferedImage image = new Robot().createScreenCapture(bounds);
        ImageIO.write(image, "png", target.toFile());
        System.out.println("saved " + target.toAbsolutePath() + " (" + bounds.width + "x" + bounds.height + ")");
    }

    private WindowDriver() {
    }
}
