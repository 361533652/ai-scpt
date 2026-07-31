package com.gmscpt.lua;

import com.gmscpt.core.ScriptContext;
import com.gmscpt.input.InputSimulator;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.Varargs;

/**
 * Mouse-related Lua API functions.
 * Mirrors Logitech G HUB scripting API conventions.
 */
public class MouseApi {

    private MouseApi() {}

    public static void register(org.luaj.vm2.Globals globals, ScriptContext context) {
        InputSimulator sim = InputSimulator.getInstance();

        // PressMouseButton(button)
        globals.set("PressMouseButton", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int button = arg.checkint();
                sim.pressMouseButton(button);
                return LuaValue.NONE;
            }
        });

        // ReleaseMouseButton(button)
        globals.set("ReleaseMouseButton", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int button = arg.checkint();
                sim.releaseMouseButton(button);
                return LuaValue.NONE;
            }
        });

        // ClickMouseButton(button)
        globals.set("ClickMouseButton", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int button = arg.checkint();
                sim.clickMouseButton(button);
                return LuaValue.NONE;
            }
        });

        // IsMouseButtonPressed(button) -> boolean
        globals.set("IsMouseButtonPressed", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int button = arg.checkint();
                boolean pressed = com.gmscpt.core.MouseKeyState.getInstance().isMouseButtonPressed(button);
                return LuaValue.valueOf(pressed);
            }
        });

        // MoveMouseTo(x, y)
        globals.set("MoveMouseTo", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue x, LuaValue y) {
                sim.moveMouseTo(x.checkint(), y.checkint());
                return LuaValue.NONE;
            }
        });

        // MoveMouseRelative(dx, dy) - supports fractional values with sub-pixel accumulation
        globals.set("MoveMouseRelative", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue dx, LuaValue dy) {
                sim.moveMouseRelativePrecise(dx.checkdouble(), dy.checkdouble());
                return LuaValue.NONE;
            }
        });

        // ResetMouseAccumulator() - reset sub-pixel accumulator between movement sequences
        globals.set("ResetMouseAccumulator", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                sim.resetAccumulator();
                return LuaValue.NONE;
            }
        });

        // GetMousePosition() -> x, y
        globals.set("GetMousePosition", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                java.awt.Point pos = sim.getMousePosition();
                return LuaValue.varargsOf(LuaValue.valueOf(pos.x), LuaValue.valueOf(pos.y));
            }
        });

        // MouseWheelScroll(amount)
        globals.set("MouseWheelScroll", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                sim.mouseWheel(arg.checkint());
                return LuaValue.NONE;
            }
        });
    }
}
