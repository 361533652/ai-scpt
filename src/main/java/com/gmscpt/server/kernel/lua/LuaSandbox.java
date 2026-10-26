package com.gmscpt.server.kernel.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Lua 沙箱。
 *
 * <p><b>F1 修复点：</b>旧版用 {@code JsePlatform.standardGlobals()}，
 * 这个 Globals 里**没有 {@code debug} 库**，于是 {@code installStopHook()} 里的
 * {@code debug.sethook} 调用直接 return——注释写着「每 5000 条指令检查一次」，
 * 实际那个钩子从来没装上。后果是 {@code while true do end} 停不住、CPU 满核、
 * 停止按钮永不复位。这里改用 {@code debugGlobals()}，并保留指令钩子。
 *
 * <p>另一半是安全：{@code standardGlobals()} 会带上 {@code os} / {@code io} / {@code luajava}，
 * AI 生成的脚本可以 {@code os.execute("...")} 或
 * {@code luajava.newInstance("java.lang.ProcessBuilder", ...)} 直接执行任意命令。
 * 对一个「让大模型写脚本自动跑」的工具来说这是必须堵死的口子。
 */
public final class LuaSandbox {

    private LuaSandbox() {
    }

    /** 沙箱里被移除的库/函数（有意的白名单式收口）。 */
    private static final String[] REMOVED_LIBS = {"os", "io", "luajava", "package", "require",
            "dofile", "loadfile", "collectgarbage"};

    /**
     * 创建一个沙箱化的 Globals。
     *
     * @return 已移除危险库、但保留 {@code debug} 的 Globals
     */
    public static Globals create() {
        Globals globals = JsePlatform.debugGlobals();
        for (String name : REMOVED_LIBS) {
            globals.set(name, LuaValue.NIL);
        }
        return globals;
    }

    /** 沙箱说明，用于日志与界面提示（让用户知道脚本里为什么没有 os/io）。 */
    public static String describe() {
        return "沙箱已移除: " + String.join(", ", REMOVED_LIBS);
    }
}
