package com.gmscpt.core;

import com.gmscpt.config.Constants;
import com.gmscpt.input.GlobalInputListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dispatches real input events to all running script engines.
 * Acts as the bridge between GlobalInputListener and ScriptEngine instances.
 */
public class EventDispatcher implements GlobalInputListener.InputEventListener {

    private static EventDispatcher instance;
    private final List<ScriptEngine> activeEngines = new CopyOnWriteArrayList<>();
    private final MouseKeyState keyState = MouseKeyState.getInstance();
    private Runnable onEmergencyStopCallback;

    private EventDispatcher() {}

    public static synchronized EventDispatcher getInstance() {
        if (instance == null) {
            instance = new EventDispatcher();
        }
        return instance;
    }

    public void registerEngine(ScriptEngine engine) {
        activeEngines.add(engine);
    }

    public void unregisterEngine(ScriptEngine engine) {
        activeEngines.remove(engine);
    }

    public void clearEngines() {
        activeEngines.clear();
    }

    public int getActiveCount() {
        return activeEngines.size();
    }

    public void setOnEmergencyStopCallback(Runnable callback) {
        this.onEmergencyStopCallback = callback;
    }

    @Override
    public void onMouseEvent(String event, int button) {
        // Update global state
        boolean pressed = Constants.EVENT_MOUSE_BUTTON_PRESSED.equals(event);
        keyState.setMouseButton(button, pressed);

        // Enqueue events for script thread processing (LuaJ is NOT thread-safe)
        for (ScriptEngine engine : activeEngines) {
            if (engine.getContext().isRunning() && !engine.getContext().isStopRequested()) {
                engine.getContext().enqueueEvent(event, button);
            }
        }
    }

    @Override
    public void onKeyEvent(String event, String keyName) {
        // Update global state
        boolean pressed = Constants.EVENT_KEY_PRESSED.equals(event);
        keyState.setKey(keyName, pressed);

        // Enqueue events for script thread processing (LuaJ is NOT thread-safe)
        for (ScriptEngine engine : activeEngines) {
            if (engine.getContext().isRunning() && !engine.getContext().isStopRequested()) {
                engine.getContext().enqueueEvent(event, keyName);
            }
        }
    }

    @Override
    public void onEmergencyStop() {
        if (onEmergencyStopCallback != null) {
            onEmergencyStopCallback.run();
        }
    }
}
