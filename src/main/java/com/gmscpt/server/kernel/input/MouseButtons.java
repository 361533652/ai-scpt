package com.gmscpt.server.kernel.input;

/**
 * 鼠标按钮编号的语义转换。
 *
 * <p><b>这是一处真实的旧版 bug。</b> 旧代码同时用了三套语义：
 * <ul>
 *   <li>Lua API 与 {@code InputSimulator}：AWT 语义 → 1=左、2=中、3=右；</li>
 *   <li>JNativeHook 钩子：Windows 原生语义 → 1=左、<b>2=右</b>、<b>3=中</b>。</li>
 * </ul>
 * 依据：JNativeHook 2.2.2 的 Windows 实现来自 libuiohook，其一比一对应关系为
 * {@code WM_LBUTTON*→MOUSE_BUTTON1}、{@code WM_RBUTTON*→MOUSE_BUTTON2}、
 * {@code WM_MBUTTON*→MOUSE_BUTTON3}、{@code XBUTTON1→4}、{@code XBUTTON2→5}
 * （见 libuiohook {@code src/windows/input_hook.c} 的 {@code mouse_hook_event_proc}）。
 * 于是旧版 {@code IsMouseButtonPressed(3)} 判断的其实是中键，右键永远为 false。
 *
 * <p>本类把「钩子语义」在边界处一次性翻译成「规范语义」，规范语义与 AWT / Lua API 一致。
 */
public final class MouseButtons {

    private MouseButtons() {
    }

    /** 规范按钮号：左。 */
    public static final int LEFT = 1;
    /** 规范按钮号：中。 */
    public static final int MIDDLE = 2;
    /** 规范按钮号：右。 */
    public static final int RIGHT = 3;
    /** 规范按钮号：侧键 1。 */
    public static final int BACK = 4;
    /** 规范按钮号：侧键 2。 */
    public static final int FORWARD = 5;

    /**
     * 钩子（JNativeHook / Windows 原生）按钮号 → 规范按钮号。
     * 2↔3 互换，其余相同。
     */
    public static int fromHook(int hookButton) {
        return switch (hookButton) {
            case 2 -> RIGHT;
            case 3 -> MIDDLE;
            default -> hookButton;
        };
    }

    /** 规范按钮号 → 钩子按钮号（仅在需要回写/比对钩子事件时使用）。 */
    public static int toHook(int canonical) {
        return switch (canonical) {
            case RIGHT -> 2;
            case MIDDLE -> 3;
            default -> canonical;
        };
    }

    /** 规范按钮号 → 稳定名字（Lua 侧与热键表达式都用这个名字）。 */
    public static String name(int canonical) {
        return "mouse" + canonical;
    }

    /**
     * 解析鼠标按钮名（{@code mouse1}..{@code mouse5}，也接受 {@code left}/{@code middle}/{@code right}）。
     *
     * @return 规范按钮号；无法解析返回 {@code 0}
     */
    public static int parseName(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase(java.util.Locale.ROOT).trim();
        switch (n) {
            case "left" -> {
                return LEFT;
            }
            case "middle" -> {
                return MIDDLE;
            }
            case "right" -> {
                return RIGHT;
            }
            default -> {
                // fallthrough
            }
        }
        if (n.startsWith("mouse") && n.length() == 6 && Character.isDigit(n.charAt(5))) {
            int b = n.charAt(5) - '0';
            return (b >= 1 && b <= 5) ? b : 0;
        }
        return 0;
    }

    /** 规范按钮号 → AWT {@code InputEvent.BUTTON*_DOWN_MASK} 的按钮常量（1/2/3），供 Robot 用。 */
    public static int toAwtMaskButton(int canonical) {
        return switch (canonical) {
            case LEFT -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
            case MIDDLE -> java.awt.event.InputEvent.BUTTON2_DOWN_MASK;
            case RIGHT -> java.awt.event.InputEvent.BUTTON3_DOWN_MASK;
            default -> 0;
        };
    }
}
