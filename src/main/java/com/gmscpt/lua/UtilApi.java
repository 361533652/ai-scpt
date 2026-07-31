package com.gmscpt.lua;

import com.gmscpt.core.ScriptContext;
import com.gmscpt.input.InputSimulator;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * Utility Lua API functions: Sleep, Log, time, random, etc.
 */
public class UtilApi {

    private UtilApi() {}

    public static void register(Globals globals, ScriptContext context) {
        InputSimulator sim = InputSimulator.getInstance();
        long startTime = System.currentTimeMillis();

        // Sleep(ms) - processes pending events, then checks stop flag
        globals.set("Sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                long ms = arg.checklong();
                // Process queued input events on the script thread (thread-safe Lua execution)
                context.processPendingEvents();
                // Check stop before sleeping
                if (context.isStopRequested()) {
                    throw new org.luaj.vm2.LuaError("Script stopped");
                }
                // Sleep in small increments to respond to stop quickly
                long remaining = ms;
                while (remaining > 0) {
                    if (context.isStopRequested()) {
                        throw new org.luaj.vm2.LuaError("Script stopped");
                    }
                    long chunk = Math.min(remaining, 50);
                    sim.sleep(chunk);
                    remaining -= chunk;
                }
                return LuaValue.NONE;
            }
        });

        // Log(message) - output to console
        globals.set("Log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String msg = arg.tojstring();
                context.log(msg);
                return LuaValue.NONE;
            }
        });

        // d - Logitech compatible
        globals.set("OutputLogMessage", new org.luaj.vm2.lib.VarArgFunction() {
            @Override
            public org.luaj.vm2.Varargs invoke(org.luaj.vm2.Varargs args) {
                if (args.narg() > 0) {
                    String fmt = args.arg(1).tojstring();
                    // Simple format: replace %d, %s, %f with args
                    StringBuilder sb = new StringBuilder();
                    int argIdx = 2;
                    for (int i = 0; i < fmt.length(); i++) {
                        char c = fmt.charAt(i);
                        if (c == '%' && i + 1 < fmt.length() && argIdx <= args.narg()) {
                            char next = fmt.charAt(i + 1);
                            if (next == 'd' || next == 'i') {
                                sb.append(args.arg(argIdx).checkint());
                                argIdx++;
                                i++;
                            } else if (next == 's') {
                                sb.append(args.arg(argIdx).tojstring());
                                argIdx++;
                                i++;
                            } else if (next == 'f') {
                                sb.append(String.format("%.2f", args.arg(argIdx).checkdouble()));
                                argIdx++;
                                i++;
                            } else if (next == '%') {
                                sb.append('%');
                                i++;
                            } else {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    context.log(sb.toString());
                }
                return LuaValue.NONE;
            }
        });

        // GetRunningTime() -> milliseconds since script started
        globals.set("GetRunningTime", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(System.currentTimeMillis() - startTime);
            }
        });

        // GetTime() -> current system time in ms
        globals.set("GetTime", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(System.currentTimeMillis());
            }
        });

        // IsScriptRunning() -> boolean
        globals.set("IsScriptRunning", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(context.isRunning() && !context.isStopRequested());
            }
        });

        // RandomInt(min, max) -> random integer in [min, max]
        globals.set("RandomInt", new org.luaj.vm2.lib.TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue min, LuaValue max) {
                int lo = min.checkint();
                int hi = max.checkint();
                int result = lo + (int)(Math.random() * (hi - lo + 1));
                return LuaValue.valueOf(result);
            }
        });
    }
}
