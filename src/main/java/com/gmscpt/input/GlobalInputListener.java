
package com.gmscpt.input;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import com.gmscpt.config.Constants;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Global input listener using JNativeHook.
 * Captures all mouse/keyboard events system-wide and dispatches them.
 * Includes emergency stop hotkey (Ctrl+Shift+F12).
 */
public class GlobalInputListener implements NativeMouseListener, NativeKeyListener {

    public interface InputEventListener {
        void onMouseEvent(String event, int button);
        void onKeyEvent(String event, String keyName);
        void onEmergencyStop();
    }

    private static GlobalInputListener instance;
    private final List<InputEventListener> listeners = new CopyOnWriteArrayList<>();
    private final InputSimulator simulator;
    private boolean registered = false;

    // JNativeHook VC_ code -> standard English key name
    // NOTE: VC_ letter constants are NOT sequential (they are keyboard scancodes)!
    private static final Map<Integer, String> NATIVE_KEY_MAP = new HashMap<>();
    static {
        // Letters A-Z (each has a unique scancode, NOT sequential!)
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_A, "a");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_B, "b");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_C, "c");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_D, "d");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_E, "e");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_F, "f");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_G, "g");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_H, "h");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_I, "i");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_J, "j");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_K, "k");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_L, "l");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_M, "m");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_N, "n");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_O, "o");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_P, "p");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_Q, "q");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_R, "r");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_S, "s");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_T, "t");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_U, "u");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_V, "v");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_W, "w");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_X, "x");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_Y, "y");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_Z, "z");
        // Digits 0-9 (sequential: VC_1=0x02..VC_9=0x0A, VC_0=0x0B)
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_0, "0");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_1, "1");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_2, "2");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_3, "3");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_4, "4");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_5, "5");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_6, "6");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_7, "7");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_8, "8");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_9, "9");
        // Function keys F1-F12 (sequential from VC_F1)
        for (int i = 0; i < 12; i++) {
            NATIVE_KEY_MAP.put(NativeKeyEvent.VC_F1 + i, "f" + (i + 1));
        }
        // Special keys
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_SPACE, "space");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_ENTER, "enter");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_TAB, "tab");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_ESCAPE, "esc");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_BACKSPACE, "backspace");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_DELETE, "delete");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_INSERT, "insert");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_SHIFT, "shift");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_CONTROL, "ctrl");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_ALT, "alt");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_CAPS_LOCK, "capslock");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_NUM_LOCK, "numlock");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_SCROLL_LOCK, "scrolllock");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_HOME, "home");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_END, "end");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_PAGE_UP, "pageup");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_PAGE_DOWN, "pagedown");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_UP, "up");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_DOWN, "down");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_LEFT, "left");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_RIGHT, "right");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_MINUS, "minus");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_EQUALS, "equals");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_COMMA, "comma");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_PERIOD, "period");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_SLASH, "slash");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_SEMICOLON, "semicolon");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_QUOTE, "quote");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_OPEN_BRACKET, "lbracket");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_CLOSE_BRACKET, "rbracket");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_BACK_SLASH, "backslash");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_BACKQUOTE, "grave");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_PRINTSCREEN, "printscreen");
        NATIVE_KEY_MAP.put(NativeKeyEvent.VC_PAUSE, "pause");
    }

    private static String resolveKeyName(int nativeKeyCode) {
        String name = NATIVE_KEY_MAP.get(nativeKeyCode);
        if (name != null) return name;
        // Fallback: try AWT mapping
        return KeyMapping.getKeyName(nativeKeyCode);
    }

    private GlobalInputListener() {
        this.simulator = InputSimulator.getInstance();
    }

    public static synchronized GlobalInputListener getInstance() {
        if (instance == null) {
            instance = new GlobalInputListener();
        }
        return instance;
    }

    public void start() {
        if (registered) return;
        try {
            // Suppress JNativeHook's verbose logging
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.OFF);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeMouseListener(this);
            GlobalScreen.addNativeKeyListener(this);
            registered = true;
        } catch (NativeHookException e) {
            System.err.println("Failed to register native hook: " + e.getMessage());
        }
    }

    public void stop() {
        if (!registered) return;
        try {
            GlobalScreen.removeNativeMouseListener(this);
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.unregisterNativeHook();
            registered = false;
        } catch (NativeHookException e) {
            System.err.println("Failed to unregister native hook: " + e.getMessage());
        }
    }

    public boolean isRegistered() {
        return registered;
    }

    public void addListener(InputEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(InputEventListener listener) {
        listeners.remove(listener);
    }

    public boolean hasListener(InputEventListener listener) {
        return listeners.contains(listener);
    }

    // ===== NativeMouseListener =====

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        int button = e.getButton();
        // Filter only the exact button being simulated
        if (simulator.isSimulatedButton(button)) return;
        for (InputEventListener l : listeners) {
            l.onMouseEvent(Constants.EVENT_MOUSE_BUTTON_PRESSED, button);
        }
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
        int button = e.getButton();
        if (simulator.isSimulatedButton(button)) return;
        for (InputEventListener l : listeners) {
            l.onMouseEvent(Constants.EVENT_MOUSE_BUTTON_RELEASED, button);
        }
    }

    @Override
    public void nativeMouseClicked(NativeMouseEvent e) {
        // Not used - we handle press/release separately
    }

    // ===== NativeKeyListener =====

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        // Check emergency stop: Ctrl + Shift + F12
        if (e.getKeyCode() == NativeKeyEvent.VC_F12) {
            int modifiers = e.getModifiers();
            if ((modifiers & NativeKeyEvent.CTRL_MASK) != 0
                    && (modifiers & NativeKeyEvent.SHIFT_MASK) != 0) {
                for (InputEventListener l : listeners) {
                    l.onEmergencyStop();
                }
                return;
            }
        }

        String keyName = resolveKeyName(e.getKeyCode());
        // Filter only the exact key being simulated
        if (isSimulatedKeyName(keyName)) return;
        for (InputEventListener l : listeners) {
            l.onKeyEvent(Constants.EVENT_KEY_PRESSED, keyName);
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        String keyName = resolveKeyName(e.getKeyCode());
        if (isSimulatedKeyName(keyName)) return;
        for (InputEventListener l : listeners) {
            l.onKeyEvent(Constants.EVENT_KEY_RELEASED, keyName);
        }
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // Not used
    }

    /**
     * Check if a key (by resolved name) is currently being simulated.
     * Converts key name to AWT code and checks InputSimulator's tracking set.
     */
    private boolean isSimulatedKeyName(String keyName) {
        int awtCode = KeyMapping.getKeyCode(keyName);
        return awtCode != -1 && simulator.isSimulatedKey(awtCode);
    }
}
