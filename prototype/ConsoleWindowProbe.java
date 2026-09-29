import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Throwaway prototype. Not part of the app.
 *
 * Decides, without relying on pixels or on conhost.exe being present, whether
 * a ffmpeg child process gets an on-screen console window.
 *
 * conhost.exe is NOT evidence: CREATE_NO_WINDOW still allocates a console, it
 * just never shows a window. The reliable signal is the set of top-level
 * windows on the desktop, so we enumerate them before and after each spawn and
 * look at what appeared. Console windows use the class ConsoleWindowClass
 * (or CASCADIA_HOSTING_WINDOW_CLASS when Windows Terminal hosts them).
 *
 * Compares two launch strategies back to back:
 *   A) plain ProcessBuilder  -- what the app would do today
 *   B) JNA CreateProcessW with CREATE_NO_WINDOW -- the proposed fix
 *
 * Run with javaw so the JVM itself has no console, which is how a jpackage
 * GUI build behaves.
 */
public final class ConsoleWindowProbe {

    static final int CREATE_NO_WINDOW = 0x08000000;

    interface Kernel32W extends StdCallLibrary {
        Kernel32W INSTANCE = Native.load("kernel32", Kernel32W.class, W32APIOptions.UNICODE_OPTIONS);

        boolean CreateProcessW(String lpApplicationName, WString lpCommandLine,
                               Pointer lpProcessAttributes, Pointer lpThreadAttributes,
                               boolean bInheritHandles, int dwCreationFlags,
                               Pointer lpEnvironment, String lpCurrentDirectory,
                               WinBase.STARTUPINFO lpStartupInfo,
                               WinBase.PROCESS_INFORMATION lpProcessInformation);

        boolean TerminateProcess(WinNT.HANDLE hProcess, int uExitCode);

        int WaitForSingleObject(WinNT.HANDLE h, int dwMilliseconds);
    }

    record Win(long hwnd, String cls, String title, int pid, boolean visible) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s pid=%d visible=%s hwnd=0x%x title='%s'",
                    cls, pid, visible, hwnd, title);
        }
    }

    static final Set<String> CONSOLE_CLASSES = new HashSet<>(List.of(
            "ConsoleWindowClass",
            "CASCADIA_HOSTING_WINDOW_CLASS"));

    static List<Win> topLevelWindows() {
        List<Win> found = new ArrayList<>();
        WinUser.WNDENUMPROC callback = (hwnd, data) -> {
            char[] cls = new char[256];
            User32.INSTANCE.GetClassName(hwnd, cls, cls.length);
            char[] title = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, title, title.length);
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            found.add(new Win(
                    Pointer.nativeValue(hwnd.getPointer()),
                    Native.toString(cls),
                    Native.toString(title),
                    pid.getValue(),
                    User32.INSTANCE.IsWindowVisible(hwnd)));
            return true;
        };
        User32.INSTANCE.EnumWindows(callback, null);
        return found;
    }

    static List<Win> consoleWindowsIn(List<Win> windows) {
        List<Win> out = new ArrayList<>();
        for (Win w : windows) {
            if (CONSOLE_CLASSES.contains(w.cls())) {
                out.add(w);
            }
        }
        return out;
    }

    static String ffmpegPath() {
        String override = System.getenv("FFMPEG_BIN");
        if (override != null && !override.isBlank()) return override;
        return Paths.get("third_party", "ffmpeg", "windows-x64", "ffmpeg.exe").toString();
    }

    static String ffmpegArgs() {
        // -re keeps it alive at real-time speed; -f null means no output device.
        return "-hide_banner -loglevel error -nostdin -re -f lavfi "
                + "-i sine=frequency=440:duration=20 -f null -";
    }

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "console-window-probe.txt");
        Path exe = Paths.get(ffmpegPath()).toAbsolutePath();

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("jvm=" + ProcessHandle.current().info().command().orElse("?"));
            w.println("ffmpeg=" + exe);

            List<Win> baseWindows = topLevelWindows();
            List<Win> baseConsoles = consoleWindowsIn(baseWindows);
            w.println("baseline top-level windows=" + baseWindows.size()
                    + ", console windows=" + baseConsoles.size());
            for (Win c : baseConsoles) w.println("  baseline console: " + c);
            w.flush();

            // ---- strategy A: plain ProcessBuilder -------------------------
            w.println();
            w.println("[A] plain ProcessBuilder");
            Process a = new ProcessBuilder(exe.toString(),
                    "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-re", "-f", "lavfi", "-i", "sine=frequency=440:duration=20",
                    "-f", "s16le", "-ar", "44100", "-ac", "2", "-").start();
            Thread drainA = drain(a.getInputStream());
            Thread.sleep(2500);

            List<Win> afterA = topLevelWindows();
            List<Win> consolesA = consoleWindowsIn(afterA);
            List<Win> newA = new ArrayList<>(consolesA);
            newA.removeAll(baseConsoles);
            w.println("  childPid=" + a.pid() + " alive=" + a.isAlive());
            w.println("  console windows now=" + consolesA.size() + ", NEW=" + newA.size());
            for (Win c : newA) w.println("  !! NEW console window: " + c);
            w.flush();
            kill(a.pid(), a);
            drainA.interrupt();

            Thread.sleep(1500);

            // ---- strategy B: CreateProcessW(CREATE_NO_WINDOW) -------------
            w.println();
            w.println("[B] CreateProcessW with CREATE_NO_WINDOW");
            String cmdline = "\"" + exe + "\" " + ffmpegArgs();
            WinBase.STARTUPINFO si = new WinBase.STARTUPINFO();
            si.cb = new WinDef.DWORD(si.size());
            si.write();
            WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();

            boolean ok = Kernel32W.INSTANCE.CreateProcessW(
                    null, new WString(cmdline), null, null,
                    false, CREATE_NO_WINDOW, null, null, si, pi);
            w.println("  CreateProcessW returned " + ok
                    + " pid=" + pi.dwProcessId + " lastError=" + Kernel32.INSTANCE.GetLastError());
            if (!ok) {
                w.println("  strategy B unavailable, stopping");
                w.flush();
                return;
            }
            pi.read();
            Thread.sleep(2500);

            List<Win> afterB = topLevelWindows();
            List<Win> consolesB = consoleWindowsIn(afterB);
            List<Win> newB = new ArrayList<>(consolesB);
            newB.removeAll(baseConsoles);
            w.println("  console windows now=" + consolesB.size() + ", NEW=" + newB.size());
            for (Win c : newB) w.println("  !! NEW console window: " + c);
            boolean childAlive = Kernel32W.INSTANCE.WaitForSingleObject(pi.hProcess, 0) == 0x102; // WAIT_TIMEOUT
            w.println("  child still running=" + childAlive);
            w.println("  descendants of this jvm=" + ProcessHandle.current().descendants()
                    .map(h -> h.info().command().orElse("?")).toList());

            Kernel32W.INSTANCE.TerminateProcess(pi.hProcess, 1);
            Kernel32.INSTANCE.CloseHandle(pi.hProcess);
            Kernel32.INSTANCE.CloseHandle(pi.hThread);
            w.println();
            w.println("state=DONE");
        }
    }

    static Thread drain(InputStream in) {
        Thread t = new Thread(() -> {
            try (InputStream s = in) {
                byte[] buf = new byte[8192];
                while (s.read(buf) != -1) {
                    // discard
                }
            } catch (IOException ignored) {
            }
        }, "drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void kill(long pid, Process p) throws InterruptedException {
        p.destroyForcibly();
        p.waitFor();
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }

    private ConsoleWindowProbe() {
    }
}
