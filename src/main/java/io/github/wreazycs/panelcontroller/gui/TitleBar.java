package io.github.wreazycs.panelcontroller.gui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

/**
 * Windows draws the title bar (with the close button) itself; JavaFX leaves it light. This switches
 * it to dark or light for every window of the app that has a title bar.
 */
final class TitleBar {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int WS_CAPTION = 0x00C00000;
    private static final int SWP_REDRAW_FRAME = 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020; // keep size, place, order, focus

    private interface Dwm extends Library {
        int DwmSetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }

    private static Dwm dwm;
    private static boolean failed;

    private TitleBar() {}

    static void apply(boolean dark) {
        if (!WINDOWS || failed) {
            return;
        }
        try {
            if (dwm == null) {
                dwm = Native.load("dwmapi", Dwm.class);
            }
            int pid = Kernel32.INSTANCE.GetCurrentProcessId();
            IntByReference value = new IntByReference(dark ? 1 : 0);
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
                if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd)
                        && (User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE) & WS_CAPTION) == WS_CAPTION) {
                    dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4);
                    User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_REDRAW_FRAME);
                }
                return true;
            }, null);
        } catch (Throwable e) {
            failed = true; // older Windows or no native access: the title bar just stays as it is
            ErrorLog.write("title bar", e);
        }
    }
}
