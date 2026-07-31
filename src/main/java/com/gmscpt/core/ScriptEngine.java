package com.gmscpt.core;

import com.gmscpt.lua.LuaApiRegistry;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Encapsulates a single Lua script runtime.
 * Each ScriptEngine has its own Globals (Lua state) with registered APIs.
 */
public class ScriptEngine {

    private final Globals globals;
    private final ScriptContext context;
    private LuaValue onEventFunc;
    private LuaValue compiledChunk;  // Stored after compile, executed on script thread

    public ScriptEngine(ScriptContext context) {
        this.context = context;
        this.globals = JsePlatform.standardGlobals();
        // Register all Lua APIs
        LuaApiRegistry.registerAll(globals, context);
        // Install debug hook to stop tight loops
        installStopHook();
        // Register event handler: events queued by hook thread will be
        // dispatched to OnEvent on the script thread during Sleep()
        context.setEventHandler((event, arg) -> {
            if (arg instanceof Integer) {
                callOnEvent(event, (Integer) arg);
            } else if (arg instanceof String) {
                callOnEvent(event, (String) arg);
            }
        });
    }

    /**
     * Uses LuaJ's debug.sethook to periodically check the stop flag.
     * Fires every 5000 Lua instructions, so even `while true do end` can be stopped.
     */
    private void installStopHook() {
        try {
            LuaValue debugTable = globals.get("debug");
            if (debugTable.isnil() || !debugTable.istable()) return;
            LuaValue sethook = debugTable.get("sethook");
            if (sethook.isnil()) return;

            LuaValue hookFunc = new org.luaj.vm2.lib.ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    if (context.isStopRequested()) {
                        throw new org.luaj.vm2.LuaError("Script stopped");
                    }
                    return LuaValue.NONE;
                }
            };

            // debug.sethook(hookFunc, "", 5000) = check every 5000 instructions
            sethook.call(hookFunc, LuaValue.valueOf(""), LuaValue.valueOf(5000));
        } catch (Exception ignored) {
            // If debug lib not available, rely on Sleep() checks only
        }
    }

    /**
     * Compile the script (syntax check only, does NOT execute top-level code).
     * Safe to call on EDT - returns quickly.
     * @return error message or null if successful
     */
    public String load(String scriptSource) {
        try {
            compiledChunk = globals.load(scriptSource, "script");
            return null; // Compile success
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Execute the script's top-level code (defines functions, runs main loop, etc.).
     * MUST be called on the script thread, NOT the EDT, because top-level code
     * may contain blocking loops (while true do ... end).
     * @return error message or null if successful
     */
    public String runMain() {
        if (compiledChunk == null) return "Script not compiled";
        try {
            compiledChunk.call(); // Execute top-level code

            // Look for OnEvent function (defined during top-level execution)
            onEventFunc = globals.get("OnEvent");
            if (onEventFunc.isnil()) {
                onEventFunc = globals.get("onEvent");
            }
            if (onEventFunc.isnil()) {
                onEventFunc = globals.get("onevent");
            }

            return null; // Success
        } catch (Exception e) {
            if (isStopError(e)) {
                return null; // Normal stop, not an error
            }
            return e.getMessage();
        }
    }

    /**
     * Call the OnEvent(event, arg) function with integer arg (mouse events).
     * Dynamically looks up OnEvent in globals each time, so it works even
     * while the main chunk is still executing (e.g. blocking top-level loop).
     */
    public void callOnEvent(String event, int arg) {
        LuaValue func = findOnEvent();
        if (func != null) {
            try {
                func.call(LuaValue.valueOf(event), LuaValue.valueOf(arg));
            } catch (Exception e) {
                if (!isStopError(e)) {
                    context.log("[ERROR] OnEvent: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Call the OnEvent(event, arg) function with string arg (key events).
     * arg is the key name, e.g. "space", "a", "f1".
     */
    public void callOnEvent(String event, String keyName) {
        LuaValue func = findOnEvent();
        if (func != null) {
            try {
                func.call(LuaValue.valueOf(event), LuaValue.valueOf(keyName));
            } catch (Exception e) {
                if (!isStopError(e)) {
                    context.log("[ERROR] OnEvent: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Find the OnEvent function - uses cached version or looks up dynamically.
     */
    private LuaValue findOnEvent() {
        if (onEventFunc != null && !onEventFunc.isnil()) {
            return onEventFunc;
        }
        // Dynamic lookup: OnEvent may have been defined before a blocking loop
        LuaValue func = globals.get("OnEvent");
        if (func.isnil()) func = globals.get("onEvent");
        if (func.isnil()) func = globals.get("onevent");
        if (!func.isnil()) {
            onEventFunc = func; // Cache for next time
            return func;
        }
        return null;
    }

    private boolean isStopError(Exception e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("Script stopped");
    }

    /**
     * Check if the script defines an OnEvent handler.
     */
    public boolean hasOnEvent() {
        return findOnEvent() != null;
    }

    public Globals getGlobals() {
        return globals;
    }

    public ScriptContext getContext() {
        return context;
    }
}
