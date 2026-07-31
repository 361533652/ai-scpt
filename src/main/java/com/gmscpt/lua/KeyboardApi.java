package com.gmscpt.lua;

import com.gmscpt.core.ScriptContext;
import com.gmscpt.input.InputSimulator;
import com.gmscpt.input.KeyMapping;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

/**
 * Keyboard-related Lua API functions.
 */
public class KeyboardApi {

    private KeyboardApi() {}

    public static void register(Globals globals, ScriptContext context) {
        InputSimulator sim = InputSimulator.getInstance();

        // PressKey(keyName)
        globals.set("PressKey", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String keyName = arg.checkjstring();
                sim.pressKeyByName(keyName);
                return LuaValue.NONE;
            }
        });

        // ReleaseKey(keyName)
        globals.set("ReleaseKey", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String keyName = arg.checkjstring();
                sim.releaseKeyByName(keyName);
                return LuaValue.NONE;
            }
        });

        // TapKey(keyName) - press and release
        globals.set("TapKey", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String keyName = arg.checkjstring();
                sim.tapKeyByName(keyName);
                return LuaValue.NONE;
            }
        });

        // IsKeyPressed(keyName) -> boolean
        globals.set("IsKeyPressed", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String keyName = arg.checkjstring();
                boolean pressed = com.gmscpt.core.MouseKeyState.getInstance().isKeyPressed(keyName);
                return LuaValue.valueOf(pressed);
            }
        });

        // PressAndReleaseKey(keyName, holdMs) - press, hold, release
        globals.set("PressAndReleaseKey", new org.luaj.vm2.lib.TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg, LuaValue holdArg) {
                String keyName = keyArg.checkjstring();
                int holdMs = holdArg.optint(50);
                sim.pressKeyByName(keyName);
                sim.sleep(holdMs);
                sim.releaseKeyByName(keyName);
                return LuaValue.NONE;
            }
        });
    }
}
