package com.gmscpt.server.kernel.input;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

/**
 * 基于 Win32 {@code SendInput} 的鼠标注入。
 *
 * <p>设计要点（对旧版的修正）：
 * <ul>
 *   <li><b>不再改系统鼠标速度</b>。旧版为了「1:1 像素」用 {@code SPI_SETMOUSESPEED} 把
 *       全局鼠标速度写成 10，进程被强杀后用户鼠标灵敏度就永久变了。这里改用
 *       <b>绝对坐标移动</b>：读当前光标（物理像素）→ 加偏移 → 用 {@code MOUSEEVENTF_ABSOLUTE}
 *       发下去。绝对移动不过系统加速曲线，本身就不需要改任何全局设置。</li>
 *   <li>{@code SendInput} 的返回值必须检查：返回 0 表示被 UIPI 拦下（目标程序以管理员运行时必然如此），
 *       旧版完全忽略，表现为「脚本在跑但鼠标不动」。这里返回布尔值并由上层报错。</li>
 *   <li>亚像素累加器归零由调用方在每次运行开始时调用 {@link #resetAccumulator()}，
 *       旧版是进程级残留，上一轮的 0.9px 会带进下一次运行。</li>
 * </ul>
 */
public final class NativeMouse {

    // Win32 常量（JNA 的 WinUser 里没有全部导出，这里按 SDK 值硬编码并注明来源）
    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;
    private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private static final int MOUSEEVENTF_RIGHTUP = 0x0010;
    private static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private static final int MOUSEEVENTF_MIDDLEUP = 0x0040;
    private static final int MOUSEEVENTF_XDOWN = 0x0080;
    private static final int MOUSEEVENTF_XUP = 0x0100;
    private static final int MOUSEEVENTF_WHEEL = 0x0800;
    private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
    private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;
    private static final int WHEEL_DELTA = 120;
    private static final int XBUTTON1 = 0x0001;
    private static final int XBUTTON2 = 0x0002;

    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            ok = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSCREEN) > 0;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private static final double[] ACCUMULATOR = new double[2];

    private NativeMouse() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** 清零亚像素累加器；每次脚本运行开始时调用。 */
    public static void resetAccumulator() {
        synchronized (ACCUMULATOR) {
            ACCUMULATOR[0] = 0d;
            ACCUMULATOR[1] = 0d;
        }
    }

    /** 当前光标位置（物理像素）；不可用时返回 null。 */
    public static int[] cursorPos() {
        if (!AVAILABLE) return null;
        WinDef.POINT p = new WinDef.POINT();
        if (!User32.INSTANCE.GetCursorPos(p)) return null;
        return new int[]{p.x, p.y};
    }

    /**
     * 相对移动（逻辑像素），带亚像素累加。
     *
     * @return 是否成功注入；false 表示被系统/权限拦下
     */
    public static boolean moveRelative(double dx, double dy) {
        if (!AVAILABLE) return false;
        int stepX;
        int stepY;
        synchronized (ACCUMULATOR) {
            ACCUMULATOR[0] += dx;
            ACCUMULATOR[1] += dy;
            stepX = roundHalfAwayFromZero(ACCUMULATOR[0]);
            stepY = roundHalfAwayFromZero(ACCUMULATOR[1]);
            ACCUMULATOR[0] -= stepX;
            ACCUMULATOR[1] -= stepY;
        }
        if (stepX == 0 && stepY == 0) return true; // 不足 1 像素，先攒着
        int[] cur = cursorPos();
        if (cur == null) return false;
        return moveAbsolute(cur[0] + stepX, cur[1] + stepY);
    }

    /** 绝对移动（物理像素），支持多屏（走 VIRTUALDESK 归一化）。 */
    public static boolean moveAbsolute(int x, int y) {
        if (!AVAILABLE) return false;
        int vx = User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN);
        int vy = User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN);
        int vw = Math.max(1, User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN));
        int vh = Math.max(1, User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN));
        long nx = Math.round((x - vx) * 65535.0 / (vw - 1));
        long ny = Math.round((y - vy) * 65535.0 / (vh - 1));

        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType("mi");
        input.input.mi.dx = new WinDef.LONG(nx);
        input.input.mi.dy = new WinDef.LONG(ny);
        input.input.mi.mouseData = new WinDef.DWORD(0);
        input.input.mi.dwFlags = new WinDef.DWORD(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK);
        input.input.mi.time = new WinDef.DWORD(0);
        input.input.mi.dwExtraInfo = new com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0);
        return send(input);
    }

    public static boolean buttonDown(int canonicalButton) {
        return button(canonicalButton, true);
    }

    public static boolean buttonUp(int canonicalButton) {
        return button(canonicalButton, false);
    }

    private static boolean button(int canonicalButton, boolean down) {
        if (!AVAILABLE) return false;
        int flags;
        int data = 0;
        switch (canonicalButton) {
            case MouseButtons.LEFT -> flags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
            case MouseButtons.MIDDLE -> flags = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
            case MouseButtons.RIGHT -> flags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
            case MouseButtons.BACK -> {
                flags = down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
                data = XBUTTON1;
            }
            case MouseButtons.FORWARD -> {
                flags = down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP;
                data = XBUTTON2;
            }
            default -> {
                return false;
            }
        }
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType("mi");
        input.input.mi.dx = new WinDef.LONG(0);
        input.input.mi.dy = new WinDef.LONG(0);
        input.input.mi.mouseData = new WinDef.DWORD(data);
        input.input.mi.dwFlags = new WinDef.DWORD(flags);
        input.input.mi.time = new WinDef.DWORD(0);
        input.input.mi.dwExtraInfo = new com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0);
        return send(input);
    }

    /** 滚轮：正数向上。 */
    public static boolean wheel(int notches) {
        if (!AVAILABLE || notches == 0) return false;
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType("mi");
        input.input.mi.dx = new WinDef.LONG(0);
        input.input.mi.dy = new WinDef.LONG(0);
        input.input.mi.mouseData = new WinDef.DWORD((long) notches * WHEEL_DELTA);
        input.input.mi.dwFlags = new WinDef.DWORD(MOUSEEVENTF_WHEEL);
        input.input.mi.time = new WinDef.DWORD(0);
        input.input.mi.dwExtraInfo = new com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0);
        return send(input);
    }

    private static boolean send(WinUser.INPUT input) {
        WinDef.DWORD sent = User32.INSTANCE.SendInput(new WinDef.DWORD(1), new WinUser.INPUT[]{input}, input.size());
        return sent != null && sent.longValue() == 1;
    }

    private static int roundHalfAwayFromZero(double v) {
        return (int) (v >= 0 ? Math.floor(v + 0.5) : Math.ceil(v - 0.5));
    }
}
