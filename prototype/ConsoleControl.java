import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Control experiment for ConsoleWindowProbe: proves the window enumeration
 * actually detects console windows, so that "0 console windows" there means
 * "none appeared" rather than "the detector is blind".
 *
 * Calls AllocConsole() on this very process -- which unconditionally creates a
 * console and shows its window -- then re-enumerates. It also reveals which
 * class Windows 11 uses to host the console (classic conhost vs Windows
 * Terminal), which the real probe needs to be looking for.
 *
 * Run with javaw.
 */
public final class ConsoleControl {

    interface Kernel32C extends StdCallLibrary {
        Kernel32C INSTANCE = Native.load("kernel32", Kernel32C.class, W32APIOptions.UNICODE_OPTIONS);

        boolean AllocConsole();

        boolean FreeConsole();

        WinDef.HWND GetConsoleWindow();
    }

    record Win(long hwnd, String cls, String title, int pid, boolean visible) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s pid=%d visible=%s hwnd=0x%x title='%s'",
                    cls, pid, visible, hwnd, title);
        }
    }

    static List<Win> topLevelWindows() {
        List<Win> found = new ArrayList<>();
        WinUser.WNDENUMPROC callback = (hwnd, data) -> {
            char[] cls = new char[256];
            User32.INSTANCE.GetClassName(hwnd, cls, cls.length);
            char[] title = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, title, title.length);
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            found.add(new Win(Pointer.nativeValue(hwnd.getPointer()),
                    Native.toString(cls), Native.toString(title),
                    pid.getValue(), User32.INSTANCE.IsWindowVisible(hwnd)));
            return true;
        };
        User32.INSTANCE.EnumWindows(callback, null);
        return found;
    }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "console-control.txt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            List<Win> before = topLevelWindows();
            w.println("before: top-level=" + before.size());
            w.println("this jvm pid=" + ProcessHandle.current().pid());
            w.flush();

            boolean allocated = Kernel32C.INSTANCE.AllocConsole();
            long err = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetLastError();
            w.println("AllocConsole=" + allocated + " lastError=" + err);
            w.flush();

            Thread.sleep(1500);

            WinDef.HWND consoleHwnd = Kernel32C.INSTANCE.GetConsoleWindow();
            w.println("GetConsoleWindow=0x" + Long.toHexString(Pointer.nativeValue(consoleHwnd.getPointer())));
            w.println("IsWindowVisible=" + User32.INSTANCE.IsWindowVisible(consoleHwnd));
            w.flush();

            List<Win> after = topLevelWindows();
            w.println("after: top-level=" + after.size());
            List<Win> newOnes = new ArrayList<>(after);
            newOnes.removeAll(before);
            w.println("new top-level windows=" + newOnes.size());
            for (Win x : newOnes) {
                w.println("  NEW: " + x);
            }
            w.println();
            w.println("-> detector works if a console window appears above");
            w.flush();

            Kernel32C.INSTANCE.FreeConsole();
            Thread.sleep(500);
        }
    }

    private ConsoleControl() {
    }
}
