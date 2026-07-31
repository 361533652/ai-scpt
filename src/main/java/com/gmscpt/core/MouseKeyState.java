package com.gmscpt.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global mouse/key state shared across all running scripts.
 * Thread-safe tracking of which buttons/keys are currently pressed.
 */
public class MouseKeyState {

    private static MouseKeyState instance;

    // Mouse button states: button number -> pressed
    private final Map<Integer, Boolean> mouseButtons = new ConcurrentHashMap<>();
    // Key states: key name -> pressed
    private final Map<String, Boolean> keys = new ConcurrentHashMap<>();

    private MouseKeyState() {}

    public static synchronized MouseKeyState getInstance() {
        if (instance == null) {
            instance = new MouseKeyState();
        }
        return instance;
    }

    public void setMouseButton(int button, boolean pressed) {
        mouseButtons.put(button, pressed);
    }

    public boolean isMouseButtonPressed(int button) {
        return mouseButtons.getOrDefault(button, false);
    }

    public void setKey(String keyName, boolean pressed) {
        keys.put(keyName.toLowerCase(), pressed);
    }

    public boolean isKeyPressed(String keyName) {
        return keys.getOrDefault(keyName.toLowerCase(), false);
    }

    public void reset() {
        mouseButtons.clear();
        keys.clear();
    }
}
