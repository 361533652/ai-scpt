package com.gmscpt.input;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps java.awt.Robot for simulating mouse/keyboard input.
 * Tracks which specific keys/buttons are currently being simulated
 * so GlobalInputListener can filter ONLY those exact events.
 */
public class InputSimulator {

    private static InputSimulator instance;
    private final Robot robot;

    // Precise tracking: which key codes are currently simulated (between press and release)
    private final Set<Integer> simulatedKeys = ConcurrentHashMap.newKeySet();
    // Recently released keys (to catch async hook events arriving after release)
    private final Map<Integer, Long> recentlyReleasedKeys = new ConcurrentHashMap<>();
    // Which mouse buttons are currently simulated
    private final Set<Integer> simulatedButtons = ConcurrentHashMap.newKeySet();
    // Recently released buttons
    private final Map<Integer, Long> recentlyReleasedButtons = new ConcurrentHashMap<>();
    // Timestamps for mouse move synthetic detection (short window)
    private volatile long lastMouseMoveTime = 0;
    // Sub-pixel accumulator for relative mouse movement (prevents drift from int truncation)
    private double accumX = 0;
    private double accumY = 0;

    private InputSimulator() {
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
            robot.setAutoWaitForIdle(false);
        } catch (AWTException e) {
            throw new RuntimeException("Failed to create Robot instance", e);
        }
    }

    public static synchronized InputSimulator getInstance() {
        if (instance == null) {
            instance = new InputSimulator();
        }
        return instance;
    }

    /**
     * Check if a key event for the given AWT key code is from our simulation.
     * Checks both currently-pressed and recently-released (within 100ms) keys.
     */
    public boolean isSimulatedKey(int awtKeyCode) {
        if (simulatedKeys.contains(awtKeyCode)) return true;
        Long releaseTime = recentlyReleasedKeys.get(awtKeyCode);
        if (releaseTime != null) {
            if (System.currentTimeMillis() - releaseTime < 100) return true;
            recentlyReleasedKeys.remove(awtKeyCode);
        }
        return false;
    }

    /**
     * Check if a mouse button event is from our simulation.
     */
    public boolean isSimulatedButton(int button) {
        if (simulatedButtons.contains(button)) return true;
        Long releaseTime = recentlyReleasedButtons.get(button);
        if (releaseTime != null) {
            if (System.currentTimeMillis() - releaseTime < 100) return true;
            recentlyReleasedButtons.remove(button);
        }
        return false;
    }

    /**
     * Check if a mouse move event is likely synthetic (within 30ms of our last move).
     */
    public boolean isSyntheticMouseMove() {
        return System.currentTimeMillis() - lastMouseMoveTime < 30;
    }

    // ===== Mouse Operations =====

    public void pressMouseButton(int button) {
        simulatedButtons.add(button);
        robot.mousePress(toMouseMask(button));
    }

    public void releaseMouseButton(int button) {
        robot.mouseRelease(toMouseMask(button));
        simulatedButtons.remove(button);
        recentlyReleasedButtons.put(button, System.currentTimeMillis());
    }

    public void clickMouseButton(int button) {
        pressMouseButton(button);
        sleep(20);
        releaseMouseButton(button);
    }

    public void moveMouseTo(int x, int y) {
        lastMouseMoveTime = System.currentTimeMillis();
        robot.mouseMove(x, y);
    }

    public void moveMouseRelative(int dx, int dy) {
        lastMouseMoveTime = System.currentTimeMillis();
        if (NativeMouseInput.isAvailable()) {
            // True relative movement via SendInput - games see this as real mouse motion
            NativeMouseInput.moveRelative(dx, dy);
        } else {
            // Fallback: Robot absolute positioning (works on desktop, not in games)
            java.awt.Point pos = java.awt.MouseInfo.getPointerInfo().getLocation();
            robot.mouseMove(pos.x + dx, pos.y + dy);
        }
    }

    /**
     * Move mouse relative with sub-pixel precision.
     * Accumulates fractional parts internally to prevent drift from integer truncation.
     * E.g. moveMouseRelativePrecise(14.95, 0) called 100 times = exactly 1495 pixels total.
     */
    public void moveMouseRelativePrecise(double dx, double dy) {
        accumX += dx;
        accumY += dy;
        int intX = (int) accumX;
        int intY = (int) accumY;
        accumX -= intX;
        accumY -= intY;
        if (intX != 0 || intY != 0) {
            moveMouseRelative(intX, intY);
        }
    }

    /**
     * Reset sub-pixel accumulator (call when starting a new movement sequence).
     */
    public void resetAccumulator() {
        accumX = 0;
        accumY = 0;
    }

    public void mouseWheel(int amount) {
        robot.mouseWheel(amount);
    }

    // ===== Keyboard Operations =====

    public void pressKey(int keyCode) {
        simulatedKeys.add(keyCode);
        robot.keyPress(keyCode);
    }

    public void releaseKey(int keyCode) {
        robot.keyRelease(keyCode);
        simulatedKeys.remove(keyCode);
        recentlyReleasedKeys.put(keyCode, System.currentTimeMillis());
    }

    public void pressKeyByName(String keyName) {
        int code = KeyMapping.getKeyCode(keyName);
        if (code != -1) {
            pressKey(code);
        }
    }

    public void releaseKeyByName(String keyName) {
        int code = KeyMapping.getKeyCode(keyName);
        if (code != -1) {
            releaseKey(code);
        }
    }

    public void tapKey(int keyCode) {
        pressKey(keyCode);
        sleep(20);
        releaseKey(keyCode);
    }

    public void tapKeyByName(String keyName) {
        int code = KeyMapping.getKeyCode(keyName);
        if (code != -1) {
            tapKey(code);
        }
    }

    // ===== Utility =====

    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void delay(long millis) {
        sleep(millis);
    }

    private int toMouseMask(int button) {
        return switch (button) {
            case 1 -> InputEvent.BUTTON1_DOWN_MASK;
            case 2 -> InputEvent.BUTTON2_DOWN_MASK;
            case 3 -> InputEvent.BUTTON3_DOWN_MASK;
            case 4 -> InputEvent.BUTTON1_DOWN_MASK; // fallback
            case 5 -> InputEvent.BUTTON3_DOWN_MASK; // fallback
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    public java.awt.Point getMousePosition() {
        return java.awt.MouseInfo.getPointerInfo().getLocation();
    }
}
