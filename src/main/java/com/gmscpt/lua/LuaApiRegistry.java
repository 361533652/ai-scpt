package com.gmscpt.lua;

import com.gmscpt.core.ScriptContext;
import org.luaj.vm2.Globals;

/**
 * Unified registry for all Lua API functions.
 * Call registerAll() to inject all APIs into a Lua Globals instance.
 */
public final class LuaApiRegistry {

    private LuaApiRegistry() {}

    /**
     * Register all available Lua APIs into the given Globals.
     */
    public static void registerAll(Globals globals, ScriptContext context) {
        MouseApi.register(globals, context);
        KeyboardApi.register(globals, context);
        UtilApi.register(globals, context);
    }

    /**
     * Returns a description of all available APIs for the AI system prompt.
     */
    public static String getApiDocumentation() {
        return """
            ## Mouse API
            - PressMouseButton(button) -- Press mouse button (1=left, 2=middle, 3=right)
            - ReleaseMouseButton(button) -- Release mouse button
            - ClickMouseButton(button) -- Click (press+release) mouse button
            - IsMouseButtonPressed(button) -> boolean -- Check if button is currently pressed
            - MoveMouseTo(x, y) -- Move mouse to absolute screen position
            - MoveMouseRelative(dx, dy) -- Move mouse relative to current position
            - GetMousePosition() -> x, y -- Get current mouse position
            - MouseWheelScroll(amount) -- Scroll mouse wheel (positive=up, negative=down)
            
            ## Keyboard API
            - PressKey(keyName) -- Press a key
            - ReleaseKey(keyName) -- Release a key
            - TapKey(keyName) -- Quick press and release
            - IsKeyPressed(keyName) -> boolean -- Check if key is currently pressed
            - PressAndReleaseKey(keyName, holdMs) -- Press, hold for holdMs, then release
            Valid keyName values (MUST use these exact English names):
              Letters: "a" to "z"
              Digits: "0" to "9"
              Function keys: "f1" to "f12"
              Special: "space", "enter", "tab", "esc", "backspace", "delete", "insert",
                       "shift", "ctrl", "alt", "capslock", "numlock", "scrolllock",
                       "home", "end", "pageup", "pagedown",
                       "up", "down", "left", "right",
                       "minus", "equals", "comma", "period", "slash",
                       "semicolon", "quote", "lbracket", "rbracket", "backslash", "grave",
                       "printscreen", "pause"
            
            ## Utility API
            - Sleep(ms) -- Pause script for milliseconds
            - Log(message) -- Print message to console
            - OutputLogMessage(format, ...) -- Logitech-style formatted log output
            - GetRunningTime() -> number -- Milliseconds since script started
            - GetTime() -> number -- Current system time in milliseconds
            - IsScriptRunning() -> boolean -- Check if script is still running
            - RandomInt(min, max) -> number -- Random integer in [min, max]
            
            ## Event Handler
            - OnEvent(event, arg) -- Called on input events:
              event: "MOUSE_BUTTON_PRESSED", "MOUSE_BUTTON_RELEASED", "KEY_PRESSED", "KEY_RELEASED"
              arg: mouse button number for mouse events (1=left,2=middle,3=right), key name string for key events (e.g. "space","a","f1")
            """;
    }
}
