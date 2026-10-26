package com.gmscpt.server.kernel.input;

import java.util.HashMap;
import java.util.Map;

/**
 * 键名 ↔ AWT VK 码 ↔ JNativeHook 原生 VC 码 的唯一权威表。
 *
 * <p>旧版 {@code GlobalInputListener} 用「F 键码连续」的假设推算 F11/F12，
 * 但实测 {@code VC_F10=68}、{@code VC_F11=87}、{@code VC_F12=88}（用 javap 从
 * jnativehook-2.2.2.jar 导出），于是 F11/F12 被解析成 {@code unknown(87)}，
 * 自注入过滤随之失效。这里改成显式表，**不做任何算术推算**。
 *
 * <p>键名规范：全小写。字母 {@code a}-{@code z}、数字 {@code 0}-{@code 9}、
 * 功能键 {@code f1}-{@code f24}、小键盘 {@code numpad0}-{@code numpad9} /
 * {@code numpad_decimal} / {@code numpad_enter}，其余为具名键。
 */
public final class KeyNames {

    private KeyNames() {
    }

    private static final Map<String, Integer> NAME_TO_NATIVE = new HashMap<>(256);
    private static final Map<Integer, String> NATIVE_TO_NAME = new HashMap<>(256);
    private static final Map<String, Integer> NAME_TO_AWT = new HashMap<>(256);

    private static void put(String name, int awtVk, int nativeVc) {
        NAME_TO_NATIVE.put(name, nativeVc);
        NATIVE_TO_NAME.put(nativeVc, name);
        NAME_TO_AWT.put(name, awtVk);
    }

    static {
        // ---- 字母：AWT VK_A(65)..VK_Z(90)，原生 VC 与 AWT 顺序完全不同，必须逐个写 ----
        String[] letters = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
                "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"};
        int[] letterNative = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
                49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
        for (int i = 0; i < letters.length; i++) {
            put(letters[i], 65 + i, letterNative[i]);
        }

        // ---- 数字 0-9 ----
        int[] digitNative = {11, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (int d = 0; d <= 9; d++) {
            put(String.valueOf(d), 48 + d, digitNative[d]);
        }

        // ---- 功能键 F1-F24：59-68、87-88、91-93、99-107（不连续，故显式列出） ----
        int[] fNative = {59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88, 91, 92, 93, 99, 100, 101, 102, 103, 104, 105, 106, 107};
        for (int i = 0; i < 24; i++) {
            put("f" + (i + 1), 112 + i, fNative[i]);
        }

        // ---- 符号与编辑键 ----
        put("escape", 27, 1);
        put("backquote", 192, 41);
        put("minus", 45, 12);
        put("equals", 61, 13);
        put("backspace", 8, 14);
        put("tab", 9, 15);
        put("capslock", 20, 58);
        put("open_bracket", 91, 26);
        put("close_bracket", 93, 27);
        put("backslash", 92, 43);
        put("semicolon", 59, 39);
        put("quote", 222, 40);
        put("enter", 10, 28);
        put("comma", 44, 51);
        put("period", 46, 52);
        put("slash", 47, 53);
        put("space", 32, 57);
        put("printscreen", 154, 3639);
        put("scrolllock", 145, 70);
        put("pause", 19, 3653);
        put("insert", 155, 3666);
        put("delete", 127, 3667);
        put("home", 36, 3655);
        put("end", 35, 3663);
        put("pageup", 33, 3657);
        put("pagedown", 34, 3665);
        put("numlock", 144, 69);
        put("separator", 83, 83);
        put("contextmenu", 525, 3677);

        // ---- 方向键 ----
        put("up", 38, 57416);
        put("down", 40, 57424);
        put("left", 37, 57419);
        put("right", 39, 57421);

        // ---- 修饰键（左/右同码，靠 JNativeHook 的 keyLocation 区分，本版不区分） ----
        put("shift", 16, 42);
        put("ctrl", 17, 29);
        put("alt", 18, 56);
        put("meta", 157, 3675);
    }

    /** 小键盘数字：原生码与主键盘数字相同，靠 {@code getKeyLocation()==KEY_LOCATION_NUMPAD} 区分。 */
    private static final String[] NUMPAD_NAMES = {"numpad0", "numpad1", "numpad2", "numpad3", "numpad4",
            "numpad5", "numpad6", "numpad7", "numpad8", "numpad9"};

    /** JNativeHook {@code NativeKeyEvent.KEY_LOCATION_NUMPAD} 的值，避免直接依赖该常量。 */
    public static final int LOCATION_NUMPAD = 4;

    /**
     * 原生事件 → 规范键名。
     *
     * @param nativeVc JNativeHook 的 {@code getKeyCode()}
     * @param location JNativeHook 的 {@code getKeyLocation()}
     * @return 规范键名；未映射的键返回 {@code unknown(N)}（绝不猜测成别的键）
     */
    public static String fromNative(int nativeVc, int location) {
        if (location == LOCATION_NUMPAD) {
            if (nativeVc >= 2 && nativeVc <= 11) {
                // 主键盘 1..9,0 对应原生 2..11；小键盘同一代码，按位置重命名
                int digit = (nativeVc == 11) ? 0 : nativeVc - 1;
                return NUMPAD_NAMES[digit];
            }
            if (nativeVc == 52) return "numpad_decimal";
            if (nativeVc == 82) return "numpad_decimal"; // 部分平台的小数点
            if (nativeVc == 28) return "numpad_enter";
        }
        String name = NATIVE_TO_NAME.get(nativeVc);
        return name != null ? name : "unknown(" + nativeVc + ")";
    }

    /** 规范键名 → 原生 VC 码；未知返回 {@code null}。 */
    public static Integer nativeCode(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("numpad") && n.length() == 7 && Character.isDigit(n.charAt(6))) {
            return NAME_TO_NATIVE.get(String.valueOf(n.charAt(6)));
        }
        return NAME_TO_NATIVE.get(n);
    }

    /** 规范键名 → AWT VK 码（供 Robot 注入用）；未知返回 {@code null}。 */
    public static Integer awtCode(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("numpad")) {
            if (n.length() == 7 && Character.isDigit(n.charAt(6))) {
                return java.awt.event.KeyEvent.VK_NUMPAD0 + (n.charAt(6) - '0');
            }
            switch (n) {
                case "numpad_decimal" -> {
                    return java.awt.event.KeyEvent.VK_DECIMAL;
                }
                case "numpad_enter" -> {
                    return java.awt.event.KeyEvent.VK_ENTER;
                }
                default -> {
                    return null;
                }
            }
        }
        return NAME_TO_AWT.get(n);
    }

    public static boolean isKnown(String name) {
        return nativeCode(name) != null;
    }

    /** 是否是修饰键（用于热键组合匹配）。 */
    public static boolean isModifier(String name) {
        if (name == null) return false;
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "ctrl", "control", "shift", "alt", "meta", "win" -> true;
            default -> false;
        };
    }

    /** 修饰键别名归一：control→ctrl、win/super→meta。 */
    public static String normalizeModifier(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return switch (n) {
            case "control" -> "ctrl";
            case "win", "super", "cmd", "command" -> "meta";
            default -> n;
        };
    }
}
