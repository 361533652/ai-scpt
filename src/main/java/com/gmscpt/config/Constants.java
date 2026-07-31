package com.gmscpt.config;

/**
 * Global constants for the application.
 */
public final class Constants {

    private Constants() {}

    public static final String APP_NAME = "GmScript";
    public static final String APP_VERSION = "1.0.0";

    // Event names (matching Logitech G HUB conventions)
    public static final String EVENT_MOUSE_BUTTON_PRESSED = "MOUSE_BUTTON_PRESSED";
    public static final String EVENT_MOUSE_BUTTON_RELEASED = "MOUSE_BUTTON_RELEASED";
    public static final String EVENT_KEY_PRESSED = "KEY_PRESSED";
    public static final String EVENT_KEY_RELEASED = "KEY_RELEASED";

    // Config directory name
    public static final String CONFIG_DIR_NAME = ".gmscpt";
    public static final String CONFIG_FILE_NAME = "config.json";

    // Console limits
    public static final int MAX_CONSOLE_LINES = 5000;

    // Emergency stop hotkey: Ctrl + Shift + F12
    public static final int EMERGENCY_STOP_KEY = java.awt.event.KeyEvent.VK_F12;
}
