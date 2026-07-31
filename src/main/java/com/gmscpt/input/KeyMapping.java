package com.gmscpt.input;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps human-readable key names to AWT KeyEvent key codes and vice versa.
 * Supports: a-z, 0-9, f1-f12, shift, ctrl, alt, space, enter, tab, esc, etc.
 */
public final class KeyMapping {

    private static final Map<String, Integer> NAME_TO_CODE = new HashMap<>();
    private static final Map<Integer, String> CODE_TO_NAME = new HashMap<>();

    static {
        // Letters
        for (char c = 'a'; c <= 'z'; c++) {
            int code = KeyEvent.VK_A + (c - 'a');
            NAME_TO_CODE.put(String.valueOf(c), code);
            CODE_TO_NAME.put(code, String.valueOf(c));
        }
        // Digits
        for (char c = '0'; c <= '9'; c++) {
            int code = KeyEvent.VK_0 + (c - '0');
            NAME_TO_CODE.put(String.valueOf(c), code);
            CODE_TO_NAME.put(code, String.valueOf(c));
        }
        // Function keys
        for (int i = 1; i <= 12; i++) {
            int code = KeyEvent.VK_F1 + (i - 1);
            NAME_TO_CODE.put("f" + i, code);
            CODE_TO_NAME.put(code, "f" + i);
        }
        // Special keys
        register("shift", KeyEvent.VK_SHIFT);
        register("ctrl", KeyEvent.VK_CONTROL);
        register("control", KeyEvent.VK_CONTROL);
        register("alt", KeyEvent.VK_ALT);
        register("space", KeyEvent.VK_SPACE);
        register("enter", KeyEvent.VK_ENTER);
        register("return", KeyEvent.VK_ENTER);
        register("tab", KeyEvent.VK_TAB);
        register("esc", KeyEvent.VK_ESCAPE);
        register("escape", KeyEvent.VK_ESCAPE);
        register("backspace", KeyEvent.VK_BACK_SPACE);
        register("delete", KeyEvent.VK_DELETE);
        register("del", KeyEvent.VK_DELETE);
        register("insert", KeyEvent.VK_INSERT);
        register("home", KeyEvent.VK_HOME);
        register("end", KeyEvent.VK_END);
        register("pageup", KeyEvent.VK_PAGE_UP);
        register("pagedown", KeyEvent.VK_PAGE_DOWN);
        register("up", KeyEvent.VK_UP);
        register("down", KeyEvent.VK_DOWN);
        register("left", KeyEvent.VK_LEFT);
        register("right", KeyEvent.VK_RIGHT);
        register("capslock", KeyEvent.VK_CAPS_LOCK);
        register("numlock", KeyEvent.VK_NUM_LOCK);
        register("scrolllock", KeyEvent.VK_SCROLL_LOCK);
        register("printscreen", KeyEvent.VK_PRINTSCREEN);
        register("pause", KeyEvent.VK_PAUSE);
        register("minus", KeyEvent.VK_MINUS);
        register("equals", KeyEvent.VK_EQUALS);
        register("comma", KeyEvent.VK_COMMA);
        register("period", KeyEvent.VK_PERIOD);
        register("slash", KeyEvent.VK_SLASH);
        register("semicolon", KeyEvent.VK_SEMICOLON);
        register("quote", KeyEvent.VK_QUOTE);
        register("lbracket", KeyEvent.VK_OPEN_BRACKET);
        register("rbracket", KeyEvent.VK_CLOSE_BRACKET);
        register("backslash", KeyEvent.VK_BACK_SLASH);
        register("grave", KeyEvent.VK_BACK_QUOTE);
    }

    private static void register(String name, int code) {
        NAME_TO_CODE.put(name, code);
        CODE_TO_NAME.putIfAbsent(code, name);
    }

    private KeyMapping() {}

    /**
     * Get key code from name. Returns -1 if not found.
     */
    public static int getKeyCode(String name) {
        if (name == null) return -1;
        Integer code = NAME_TO_CODE.get(name.toLowerCase().trim());
        return code != null ? code : -1;
    }

    /**
     * Get key name from code. Returns "unknown" if not found.
     */
    public static String getKeyName(int keyCode) {
        String name = CODE_TO_NAME.get(keyCode);
        return name != null ? name : "unknown(" + keyCode + ")";
    }
}
