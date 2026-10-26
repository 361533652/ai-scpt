package com.gmscpt.server.kernel.lua;

import com.gmscpt.server.kernel.input.InputEventType;
import com.gmscpt.server.kernel.input.KeyNames;
import com.gmscpt.server.kernel.input.MouseButtons;
import com.gmscpt.server.kernel.input.RobotInputGateway;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lua 暴露给脚本的全部函数与常量，**同时也是文档源**。
 *
 * <p>同一个对象喂给三处：脚本运行时注册、编辑器补全（{@code GET /api/lua/api}）、
 * AI 的系统提示词。旧版这三者是各写各的，提示词里少了 {@code ResetMouseAccumulator}、
 * 键盘键名表也不全，于是 AI 生成的脚本经常调用不存在的函数。
 */
public final class LuaApiRegistry implements LuaApiDoc {

    private record Entry(String name, String signature, String doc, LuaValue function) {
    }

    private final HostContext ctx;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, LuaValue> constants = new LinkedHashMap<>();

    private LuaApiRegistry(HostContext ctx) {
        this.ctx = ctx;
        buildConstants();
        buildMouseApi();
        buildKeyboardApi();
        buildUtilApi();
    }

    public static LuaApiRegistry create(HostContext ctx) {
        return new LuaApiRegistry(ctx);
    }

    /** 把全部函数与常量注入到 Globals。 */
    public void registerAll(Globals globals) {
        for (Entry e : entries) {
            globals.set(e.name(), e.function());
        }
        for (Map.Entry<String, LuaValue> c : constants.entrySet()) {
            globals.set(c.getKey(), c.getValue());
        }
        globals.set("EVENT_MOUSE_BUTTON_PRESSED", LuaValue.valueOf(InputEventType.MOUSE_BUTTON_PRESSED.code()));
        globals.set("EVENT_MOUSE_BUTTON_RELEASED", LuaValue.valueOf(InputEventType.MOUSE_BUTTON_RELEASED.code()));
        globals.set("EVENT_KEY_PRESSED", LuaValue.valueOf(InputEventType.KEY_PRESSED.code()));
        globals.set("EVENT_KEY_RELEASED", LuaValue.valueOf(InputEventType.KEY_RELEASED.code()));
    }

    private void buildConstants() {
        constants.put("MOUSE_BUTTON_PRESSED", LuaValue.valueOf(InputEventType.MOUSE_BUTTON_PRESSED.code()));
        constants.put("MOUSE_BUTTON_RELEASED", LuaValue.valueOf(InputEventType.MOUSE_BUTTON_RELEASED.code()));
        constants.put("KEY_PRESSED", LuaValue.valueOf(InputEventType.KEY_PRESSED.code()));
        constants.put("KEY_RELEASED", LuaValue.valueOf(InputEventType.KEY_RELEASED.code()));
        constants.put("MOUSE_BUTTON_LEFT", LuaValue.valueOf(MouseButtons.LEFT));
        constants.put("MOUSE_BUTTON_MIDDLE", LuaValue.valueOf(MouseButtons.MIDDLE));
        constants.put("MOUSE_BUTTON_RIGHT", LuaValue.valueOf(MouseButtons.RIGHT));
        constants.put("MOUSE_BUTTON_BACK", LuaValue.valueOf(MouseButtons.BACK));
        constants.put("MOUSE_BUTTON_FORWARD", LuaValue.valueOf(MouseButtons.FORWARD));
    }

    private void add(String name, String signature, String doc, VarArgFunction fn) {
        entries.add(new Entry(name, signature, doc, fn));
    }

    // ---------------- 鼠标 ----------------

    private void buildMouseApi() {
        add("MoveMouseRelative", "MoveMouseRelative(dx, dy)",
                "相对移动鼠标（像素，可为小数，内部有亚像素累加，不漂移）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().moveRelative(args.checkdouble(1), args.checkdouble(2));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("MoveMouseTo", "MoveMouseTo(x, y)",
                "把鼠标移到屏幕绝对坐标（物理像素）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().moveAbsolute(args.checkint(1), args.checkint(2));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("GetMousePosition", "GetMousePosition() -> x, y",
                "返回当前鼠标坐标（两个返回值）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        return LuaValue.varargsOf(LuaInteger.valueOf(ctx.gateway().mouseX()),
                                LuaInteger.valueOf(ctx.gateway().mouseY()));
                    }
                });

        add("PressMouseButton", "PressMouseButton(button)",
                "按下鼠标键。button 用 MOUSE_BUTTON_LEFT/MIDDLE/RIGHT/BACK/FORWARD 或 1..5。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().mouseDown(button(args, 1));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("ReleaseMouseButton", "ReleaseMouseButton(button)",
                "松开鼠标键。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().mouseUp(button(args, 1));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("ClickMouseButton", "ClickMouseButton(button)",
                "按一下再松开鼠标键。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().clickMouseButton(button(args, 1));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("IsMouseButtonPressed", "IsMouseButtonPressed(button) -> boolean",
                "该鼠标键当前是否按下（含本脚本注入的按下）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        return LuaValue.valueOf(ctx.gateway().isMouseButtonPressed(button(args, 1)));
                    }
                });

        add("ScrollMouse", "ScrollMouse(notches)",
                "滚轮滚动，正数向上，1 格 = 1。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().scroll(args.checkint(1));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("ResetMouseAccumulator", "ResetMouseAccumulator()",
                "清零相对移动的亚像素累加器（每次运行开始已自动清零）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        RobotInputGateway.resetAccumulator();
                        return LuaValue.NONE;
                    }
                });
    }

    // ---------------- 键盘 ----------------

    private void buildKeyboardApi() {
        add("PressKey", "PressKey(key)",
                "按下一个键并保持。key 为规范键名，如 \"w\"、\"f8\"、\"numpad1\"、\"ctrl\"、\"space\"。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().keyDown(key(args, 1));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("ReleaseKey", "ReleaseKey(key)",
                "松开一个键。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.gateway().keyUp(key(args, 1));
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("PressAndReleaseKey", "PressAndReleaseKey(key [, holdMs])",
                "按下、保持 holdMs 毫秒（默认 50）、松开。保持期间会继续派发 OnEvent。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        String k = key(args, 1);
                        long hold = args.narg() >= 2 ? Math.max(0, args.checklong(2)) : 50L;
                        ctx.gateway().pressAndReleaseKey(k, hold, millis -> {
                            try {
                                ctx.sleepWithPump(millis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new StoppedException("脚本已被请求停止");
                            }
                        });
                        ctx.incAction();
                        return LuaValue.NONE;
                    }
                });

        add("IsKeyPressed", "IsKeyPressed(key) -> boolean",
                "该键当前是否按下（含本脚本注入的按下）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        return LuaValue.valueOf(ctx.gateway().isKeyPressed(key(args, 1)));
                    }
                });

        add("IsModifierPressed", "IsModifierPressed(modifier) -> boolean",
                "修饰键是否按下，modifier 取 \"ctrl\"/\"shift\"/\"alt\"/\"meta\"。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        String m = KeyNames.normalizeModifier(args.checkjstring(1));
                        return LuaValue.valueOf(ctx.inputState().isKeyDown(m));
                    }
                });
    }

    // ---------------- 工具 ----------------

    private void buildUtilApi() {
        add("Sleep", "Sleep(ms)",
                "睡眠 ms 毫秒。**睡眠期间 OnEvent 仍会被派发**（与 Logitech 语义一致）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        try {
                            ctx.sleepWithPump(Math.max(0, args.checklong(1)));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new StoppedException("脚本已被请求停止");
                        }
                        return LuaValue.NONE;
                    }
                });

        add("OutputLogMessage", "OutputLogMessage(fmt, ...)",
                "把一条消息写进控制台日志。支持 string.format 风格占位符。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        ctx.log(format(args));
                        return LuaValue.NONE;
                    }
                });

        add("GetRunningTime", "GetRunningTime() -> ms",
                "本次运行已经过的毫秒数（从运行开始算起）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        return LuaValue.valueOf((int) (System.currentTimeMillis() - ctx.runStartMillis()));
                    }
                });

        add("GetDate", "GetDate([format]) -> string",
                "返回当前日期时间字符串，默认格式 yyyy-MM-dd HH:mm:ss。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        String fmt = args.narg() >= 1 && !args.arg(1).isnil()
                                ? args.checkjstring(1) : "yyyy-MM-dd HH:mm:ss";
                        return LuaValue.valueOf(new SimpleDateFormat(fmt, Locale.ROOT).format(new Date()));
                    }
                });

        add("GetTime", "GetTime() -> seconds",
                "返回 Unix 时间戳（秒）。",
                new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs args) {
                        return LuaValue.valueOf(System.currentTimeMillis() / 1000L);
                    }
                });
    }

    // ---------------- 参数辅助 ----------------

    private int button(Varargs args, int index) {
        int b = args.checkint(index);
        if (b < MouseButtons.LEFT || b > MouseButtons.FORWARD) {
            throw new LuaError("鼠标按钮必须是 1..5，收到 " + b);
        }
        return b;
    }

    private String key(Varargs args, int index) {
        String raw = args.checkjstring(index);
        String name = raw.toLowerCase(Locale.ROOT).trim();
        if (KeyNames.nativeCode(name) == null) {
            throw new LuaError("未知键名: " + raw + "（可用键名见编辑器补全或 GET /api/lua/api）");
        }
        return name;
    }

    private String format(Varargs args) {
        String fmt = args.arg(1).tojstring();
        int n = args.narg();
        if (n <= 1) return fmt;
        Object[] values = new Object[n - 1];
        for (int i = 2; i <= n; i++) {
            LuaValue v = args.arg(i);
            if (v.isint() || v.islong()) values[i - 2] = v.tolong();
            else if (v.isnumber()) values[i - 2] = v.todouble();
            else if (v.isboolean()) values[i - 2] = v.toboolean();
            else values[i - 2] = v.tojstring();
        }
        try {
            return String.format(fmt, values);
        } catch (RuntimeException e) {
            return fmt + " " + java.util.Arrays.toString(values);
        }
    }

    // ---------------- LuaApiDoc ----------------

    @Override
    public List<LuaFunction> functions() {
        List<LuaFunction> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(new LuaFunction(e.name(), e.signature(), e.doc()));
        }
        return out;
    }

    @Override
    public List<String> keyNames() {
        return List.of("a-z", "0-9", "f1-f24", "numpad0-numpad9", "numpad_decimal", "numpad_enter",
                "escape", "tab", "capslock", "shift", "ctrl", "alt", "meta", "space", "enter",
                "backspace", "delete", "insert", "home", "end", "pageup", "pagedown",
                "up", "down", "left", "right", "numlock", "scrolllock", "pause", "printscreen",
                "minus", "equals", "comma", "period", "slash", "semicolon", "quote",
                "open_bracket", "close_bracket", "backslash", "backquote");
    }

    @Override
    public List<String> mouseButtonNames() {
        return List.of("mouse1(左)", "mouse2(中)", "mouse3(右)", "mouse4(侧1)", "mouse5(侧2)");
    }

    @Override
    public List<String> eventConstants() {
        return List.of("MOUSE_BUTTON_PRESSED=" + InputEventType.MOUSE_BUTTON_PRESSED.code(),
                "MOUSE_BUTTON_RELEASED=" + InputEventType.MOUSE_BUTTON_RELEASED.code(),
                "KEY_PRESSED=" + InputEventType.KEY_PRESSED.code(),
                "KEY_RELEASED=" + InputEventType.KEY_RELEASED.code());
    }

    @Override
    public String systemPromptFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("## GmScript Lua API（只能使用下列函数，禁止发明其他函数）\n");
        sb.append("脚本入口必须是 `function OnEvent(event, arg) ... end`；顶层代码只做初始化。\n");
        sb.append("事件常量：").append(String.join("、", eventConstants())).append("\n");
        sb.append("鼠标按钮：1=左 2=中 3=右 4=侧1 5=侧2（也可用 MOUSE_BUTTON_LEFT 等常量）。\n");
        sb.append("可用键名：").append(String.join(", ", keyNames())).append("\n\n");
        for (Entry e : entries) {
            sb.append("- `").append(e.signature()).append("` ").append(e.doc()).append("\n");
        }
        sb.append("\n脚本中没有 os/io/require/loadfile，不能读写文件或执行系统命令。\n");
        return sb.toString();
    }
}
