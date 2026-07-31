package com.gmscpt.input;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Native Windows mouse input using SendInput API.
 * Generates TRUE relative mouse movement events (MOUSEEVENTF_MOVE without ABSOLUTE),
 * which is what games expect for camera/aim control.
 *
 * Unlike Robot.mouseMove() which generates absolute positioning events,
 * this produces the same type of events as physically moving the mouse.
 */
public class NativeMouseInput {

    // dwFlags constants
    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;
    private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private static final int MOUSEEVENTF_RIGHTUP = 0x0010;
    private static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private static final int MOUSEEVENTF_MIDDLEUP = 0x0040;
    private static final int MOUSEEVENTF_WHEEL = 0x0800;

    // Input type
    private static final int INPUT_MOUSE = 0;

    // SystemParametersInfo constants (ONLY speed, NEVER touch acceleration)
    private static final int SPI_GETMOUSESPEED = 0x0070;
    private static final int SPI_SETMOUSESPEED = 0x0071;
    private static final int SPIF_SENDWININICHANGE = 0x0002;

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        int SendInput(int nInputs, INPUT[] pInputs, int cbSize);

        boolean SystemParametersInfoW(int uiAction, int uiParam, Pointer pvParam, int fWinIni);
    }

    // MOUSEINPUT structure
    @Structure.FieldOrder({"dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo"})
    public static class MOUSEINPUT extends Structure {
        public int dx;
        public int dy;
        public int mouseData;
        public int dwFlags;
        public int time;
        public long dwExtraInfo;
    }

    // INPUT structure (union wrapper)
    @Structure.FieldOrder({"type", "mi"})
    public static class INPUT extends Structure {
        public int type;
        public MOUSEINPUT mi;

        public INPUT() {
            mi = new MOUSEINPUT();
        }
    }

    private static boolean available = false;
    private static int savedMouseSpeed = -1;

    static {
        try {
            // Test if SendInput is accessible
            User32.INSTANCE.toString();
            available = true;
        } catch (Throwable e) {
            System.err.println("[NativeMouseInput] SendInput not available: " + e.getMessage());
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Save current mouse speed and set to 10 (=1:1 pixel, no multiplier).
     * This ensures SendInput dx/dy values map exactly to screen pixels.
     * Does NOT touch acceleration settings at all.
     * Call when script starts.
     */
    public static void beginPreciseMode() {
        if (!available) return;
        try {
            // pvParam receives the current speed as an integer value
            com.sun.jna.Memory mem = new com.sun.jna.Memory(4);
            mem.clear(4);
            if (User32.INSTANCE.SystemParametersInfoW(SPI_GETMOUSESPEED, 0, mem, 0)) {
                savedMouseSpeed = mem.getInt(0);
            } else {
                savedMouseSpeed = 10; // fallback default
            }
            // Set speed to 10 = neutral 1:1 multiplier
            if (savedMouseSpeed != 10) {
                User32.INSTANCE.SystemParametersInfoW(SPI_SETMOUSESPEED, 10, null, SPIF_SENDWININICHANGE);
            }
        } catch (Throwable e) {
            System.err.println("[NativeMouseInput] Failed to set precise mode: " + e.getMessage());
        }
    }

    /**
     * Restore original mouse speed.
     * Call when script stops or program exits.
     */
    public static void endPreciseMode() {
        if (!available || savedMouseSpeed < 0) return;
        try {
            if (savedMouseSpeed != 10) {
                User32.INSTANCE.SystemParametersInfoW(SPI_SETMOUSESPEED, savedMouseSpeed, null, SPIF_SENDWININICHANGE);
            }
            savedMouseSpeed = -1;
        } catch (Throwable e) {
            System.err.println("[NativeMouseInput] Failed to restore mouse speed: " + e.getMessage());
        }
    }

    /**
     * Send a true relative mouse movement event.
     * This is equivalent to physically moving the mouse by (dx, dy) pixels.
     * Games using Raw Input / DirectInput will see this as real mouse motion.
     */
    public static void moveRelative(int dx, int dy) {
        if (dx == 0 && dy == 0) return;

        INPUT input = new INPUT();
        input.type = INPUT_MOUSE;
        input.mi.dx = dx;
        input.mi.dy = dy;
        input.mi.mouseData = 0;
        input.mi.dwFlags = MOUSEEVENTF_MOVE; // Relative move, NO ABSOLUTE flag
        input.mi.time = 0;
        input.mi.dwExtraInfo = 0;

        User32.INSTANCE.SendInput(1, new INPUT[]{input}, input.size());
    }

    /**
     * Send a mouse button press event.
     */
    public static void pressButton(int button) {
        INPUT input = new INPUT();
        input.type = INPUT_MOUSE;
        input.mi.dx = 0;
        input.mi.dy = 0;
        input.mi.mouseData = 0;
        input.mi.dwFlags = switch (button) {
            case 1 -> MOUSEEVENTF_LEFTDOWN;
            case 2 -> MOUSEEVENTF_MIDDLEDOWN;
            case 3 -> MOUSEEVENTF_RIGHTDOWN;
            default -> MOUSEEVENTF_LEFTDOWN;
        };
        input.mi.time = 0;
        input.mi.dwExtraInfo = 0;

        User32.INSTANCE.SendInput(1, new INPUT[]{input}, input.size());
    }

    /**
     * Send a mouse button release event.
     */
    public static void releaseButton(int button) {
        INPUT input = new INPUT();
        input.type = INPUT_MOUSE;
        input.mi.dx = 0;
        input.mi.dy = 0;
        input.mi.mouseData = 0;
        input.mi.dwFlags = switch (button) {
            case 1 -> MOUSEEVENTF_LEFTUP;
            case 2 -> MOUSEEVENTF_MIDDLEUP;
            case 3 -> MOUSEEVENTF_RIGHTUP;
            default -> MOUSEEVENTF_LEFTUP;
        };
        input.mi.time = 0;
        input.mi.dwExtraInfo = 0;

        User32.INSTANCE.SendInput(1, new INPUT[]{input}, input.size());
    }

    /**
     * Send a mouse wheel event.
     * @param amount positive = scroll up, negative = scroll down
     */
    public static void scrollWheel(int amount) {
        INPUT input = new INPUT();
        input.type = INPUT_MOUSE;
        input.mi.dx = 0;
        input.mi.dy = 0;
        input.mi.mouseData = amount * 120; // WHEEL_DELTA = 120
        input.mi.dwFlags = MOUSEEVENTF_WHEEL;
        input.mi.time = 0;
        input.mi.dwExtraInfo = 0;

        User32.INSTANCE.SendInput(1, new INPUT[]{input}, input.size());
    }
}
