package com.gmscpt.server.kernel.lua;

import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.domain.LogEntry;
import com.gmscpt.server.domain.RunExit;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.domain.ScriptStatus;
import com.gmscpt.server.kernel.input.FakeInputGateway;
import com.gmscpt.server.kernel.input.InputEvent;
import com.gmscpt.server.kernel.input.InputEventType;
import com.gmscpt.server.kernel.input.InputState;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.log.RingLogBus;
import com.gmscpt.server.kernel.runtime.StopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua 运行时的回归测试——覆盖旧版最致命的几条：
 * 停止机制形同虚设（F1）、事件只在 Sleep 里派发（M1）、队列无界（F2）、停止后按键卡死（H1）。
 */
class LuaRuntimeTest {

    private ScriptRuntime newRuntime(String source, FakeInputGateway gateway, InputState state,
                                     LogBus logBus, int queueCapacity) {
        GmScriptProperties props = new GmScriptProperties();
        props.getRuntime().setEventQueueCapacity(queueCapacity);
        props.getRuntime().setStopGraceMs(3000);
        Script script = new Script("s1", "测试脚本", "", source, 1, ScriptStatus.ENABLED,
                Instant.now(), Instant.now());
        return new ScriptRuntime(script, "run-1", "test", props.getRuntime(), logBus, gateway, state, null);
    }

    private LogBus newLogBus() {
        return new RingLogBus(null, new GmScriptProperties());
    }

    // ---------------- 沙箱 ----------------

    @Test
    @DisplayName("沙箱：os/io/luajava/require 被移除，debug 保留（停止钩子依赖它）")
    void sandboxRemovesDangerousLibraries() {
        Globals g = LuaSandbox.create();
        assertTrue(g.get("os").isnil(), "os 必须移除，否则 AI 生成的脚本能执行系统命令");
        assertTrue(g.get("io").isnil(), "io 必须移除");
        assertTrue(g.get("luajava").isnil(), "luajava 必须移除，否则可以直接 new ProcessBuilder");
        assertTrue(g.get("require").isnil());
        assertTrue(g.get("dofile").isnil());
        assertTrue(g.get("loadfile").isnil());
        assertFalse(g.get("debug").isnil(), "debug 必须保留：停止用的指令钩子挂在它上面");
        assertFalse(g.get("debug").get("sethook").isnil());
        assertFalse(g.get("string").isnil(), "基础库要保留");
        assertFalse(g.get("math").isnil());
    }

    @Test
    @DisplayName("编译校验：只编译不执行，能给出出错行号")
    void compilerReportsLineNumbers() {
        LuaCompiler.Result ok = LuaCompiler.compile("t", "function OnEvent(e,a) end\n");
        assertTrue(ok.ok());

        LuaCompiler.Result bad = LuaCompiler.compile("t", "local x = 1\nif x then\n  print('a')\n");
        assertFalse(bad.ok());
        assertFalse(bad.diagnostics().isEmpty());
        assertNotNull(bad.diagnostics().get(0).message());

        assertTrue(LuaCompiler.Result.success().ok());
        assertTrue(LuaCompiler.hasOnEvent("function OnEvent(e, a) end"));
        assertFalse(LuaCompiler.hasOnEvent("local t = 1"));
    }

    // ---------------- F1：死循环必须停得下来 ----------------

    @Test
    @DisplayName("F1：不含 Sleep 的死循环可以被停止（旧版这里永远停不住）")
    void infiniteLoopCanBeStopped() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        ScriptRuntime runtime = newRuntime("while true do end\n", gateway, new InputState(), newLogBus(), 64);
        runtime.start();

        Thread.sleep(400);
        assertTrue(runtime.isAlive(), "脚本应该正在跑");

        runtime.requestStop(StopReason.USER);
        boolean stopped = runtime.awaitStop(3000);
        assertTrue(stopped, "死循环必须在宽限期内停下来（说明 debug 指令钩子真的装上了）");
        assertEquals(RunExit.STOPPED, runtime.exit());
    }

    // ---------------- M1：事件派发不依赖 Sleep ----------------

    @Test
    @DisplayName("M1：不调用 Sleep 的脚本同样能收到 OnEvent")
    void eventsAreDispatchedWithoutSleep() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        LogBus logBus = newLogBus();
        ScriptRuntime runtime = newRuntime("""
                function OnEvent(event, arg)
                    OutputLogMessage("evt:" .. tostring(event) .. "/" .. tostring(arg))
                end
                """, gateway, new InputState(), logBus, 64);
        runtime.start();
        Thread.sleep(200);

        runtime.offer(InputEvent.key(System.currentTimeMillis(), InputEventType.KEY_PRESSED, "f8"));
        runtime.offer(InputEvent.mouse(System.currentTimeMillis(), InputEventType.MOUSE_BUTTON_PRESSED, 3));

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !hasLog(logBus, "evt:1/3")) {
            Thread.sleep(50);
        }
        runtime.requestStop(StopReason.USER);
        runtime.awaitStop(3000);

        assertTrue(hasLog(logBus, "evt:3/"), "键盘按下事件必须被派发（旧版只有 Sleep 才泵事件）");
        // 鼠标按下的常量码是 1（MOUSE_BUTTON_PRESSED），arg 是规范按钮号 3=右键
        assertTrue(hasLog(logBus, "evt:1/3"), "鼠标事件必须带上规范按钮号 3（右键）");
        assertTrue(runtime.eventCount() >= 2);
    }

    @Test
    @DisplayName("M1：Sleep 期间事件照常派发")
    void eventsAreDispatchedDuringSleep() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        LogBus logBus = newLogBus();
        ScriptRuntime runtime = newRuntime("""
                function OnEvent(event, arg)
                    OutputLogMessage("got:" .. tostring(event))
                end

                local stopAt = GetRunningTime() + 1500
                while GetRunningTime() < stopAt do
                    Sleep(100)
                end
                """, gateway, new InputState(), logBus, 64);
        runtime.start();
        Thread.sleep(200);
        runtime.offer(InputEvent.key(System.currentTimeMillis(), InputEventType.KEY_PRESSED, "a"));

        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline && !hasLog(logBus, "got:3")) {
            Thread.sleep(50);
        }
        assertTrue(hasLog(logBus, "got:3"), "Sleep 期间也必须派发 OnEvent");
        runtime.requestStop(StopReason.USER);
        runtime.awaitStop(3000);
    }

    // ---------------- F2 / H1 ----------------

    @Test
    @DisplayName("F2：队列有界，脚本不让出执行权时丢事件并计数，而不是 OOM")
    void boundedQueueDropsInsteadOfOom() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        ScriptRuntime runtime = newRuntime("while true do end\n", gateway, new InputState(), newLogBus(), 64);
        runtime.start();
        Thread.sleep(150);
        for (int i = 0; i < 1000; i++) {
            runtime.offer(InputEvent.key(System.currentTimeMillis(), InputEventType.KEY_PRESSED, "a"));
        }
        assertTrue(runtime.droppedEvents() > 0, "无界队列会 OOM，有界队列必须报告丢弃数");
        runtime.requestStop(StopReason.USER);
        assertTrue(runtime.awaitStop(3000));
    }

    @Test
    @DisplayName("H1：脚本按下 W 后被杀停，按键必须被释放（旧版会永久卡键）")
    void keysAreReleasedOnStop() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        InputState state = new InputState();
        ScriptRuntime runtime = newRuntime("""
                function OnEvent(event, arg)
                    PressKey("w")
                    Sleep(100000)
                end
                """, gateway, state, newLogBus(), 64);
        runtime.start();
        Thread.sleep(200);
        runtime.offer(InputEvent.key(System.currentTimeMillis(), InputEventType.KEY_PRESSED, "f8"));
        Thread.sleep(500);
        assertTrue(gateway.downKeys.contains("w"), "脚本应该真的按住了 W");

        runtime.requestStop(StopReason.USER);
        assertTrue(runtime.awaitStop(3000));
        assertTrue(gateway.downKeys.isEmpty(), "停止后必须松开 W，否则就是卡键");
    }

    @Test
    @DisplayName("OnEvent 报错不会让整个运行崩掉，错误会进日志")
    void onEventErrorsAreLoggedAndDoNotKillTheRun() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        LogBus logBus = newLogBus();
        ScriptRuntime runtime = newRuntime("""
                function OnEvent(event, arg)
                    error("故意报错")
                end
                """, gateway, new InputState(), logBus, 64);
        runtime.start();
        Thread.sleep(200);
        runtime.offer(InputEvent.key(System.currentTimeMillis(), InputEventType.KEY_PRESSED, "f8"));

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !hasLog(logBus, "OnEvent 报错")) {
            Thread.sleep(50);
        }
        assertTrue(hasLog(logBus, "OnEvent 报错"));
        assertTrue(runtime.isAlive(), "单次事件报错不应该结束整个运行");

        runtime.requestStop(StopReason.USER);
        runtime.awaitStop(3000);
        assertEquals(RunExit.STOPPED, runtime.exit());
    }

    @Test
    @DisplayName("未知键名与非法鼠标按钮被明确拒绝，而不是静默什么也不做")
    void invalidArgumentsAreRejected() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        LogBus logBus = newLogBus();
        ScriptRuntime runtime = newRuntime("""
                function OnEvent(event, arg)
                    PressKey("这不是键")
                end
                """, gateway, new InputState(), logBus, 64);
        runtime.start();
        Thread.sleep(200);
        runtime.offer(InputEvent.key(System.currentTimeMillis(), InputEventType.KEY_PRESSED, "f8"));

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !hasLog(logBus, "未知键名")) {
            Thread.sleep(50);
        }
        assertTrue(hasLog(logBus, "未知键名"), "未知键名必须报错，不能静默忽略");
        runtime.requestStop(StopReason.USER);
        runtime.awaitStop(3000);
    }

    @Test
    @DisplayName("同步摘要：LogBus 环形缓冲能取到最近的日志")
    void logBusKeepsRecentEntries() {
        LogBus logBus = newLogBus();
        for (int i = 0; i < 10; i++) {
            logBus.info("test", "line-" + i);
        }
        List<LogEntry> recent = logBus.recent(5);
        assertEquals(5, recent.size());
        assertEquals("line-9", recent.get(4).message());
        assertTrue(logBus.lastSeq() >= 10);
        assertFalse(logBus.since(5, 100).isEmpty());
    }

    private static boolean hasLog(LogBus logBus, String needle) {
        for (LogEntry e : logBus.recent(500)) {
            if (e.message() != null && e.message().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("停止是幂等的，重复请求不会出问题")
    void stopIsIdempotent() throws Exception {
        FakeInputGateway gateway = new FakeInputGateway();
        ScriptRuntime runtime = newRuntime("while true do end\n", gateway, new InputState(), newLogBus(), 64);
        runtime.start();
        TimeUnit.MILLISECONDS.sleep(200);
        runtime.requestStop(StopReason.USER);
        runtime.requestStop(StopReason.EMERGENCY);
        assertTrue(runtime.awaitStop(3000));
        runtime.requestStop(StopReason.USER);
        assertThrows(UnsupportedOperationException.class, () -> DocumentationHostContext.INSTANCE.gateway());
    }

    @Test
    @DisplayName("沙箱文档上下文一旦被执行就报错，避免误用")
    void documentationContextNeverExecutes() {
        LuaApiDoc doc = LuaApiRegistry.create(DocumentationHostContext.INSTANCE);
        assertFalse(doc.functions().isEmpty());
        assertTrue(doc.systemPromptFragment().contains("PressAndReleaseKey"));
        assertTrue(doc.systemPromptFragment().contains("ResetMouseAccumulator"));
        assertTrue(doc.systemPromptFragment().contains("Lua"));
    }

    @Test
    @DisplayName("LuaApiRegistry 注册的函数名与文档一一对应（防止提示词漂移）")
    void registryAndDocAgree() {
        LuaApiRegistry registry = LuaApiRegistry.create(DocumentationHostContext.INSTANCE);
        Globals globals = LuaSandbox.create();
        registry.registerAll(globals);
        for (LuaApiDoc.LuaFunction f : registry.functions()) {
            LuaValue v = globals.get(f.name());
            assertFalse(v.isnil(), "文档里写了 " + f.name() + " 但没注册到 Globals");
        }
        assertFalse(globals.get("MOUSE_BUTTON_PRESSED").isnil());
        assertFalse(globals.get("KEY_PRESSED").isnil());
        assertEquals(3, globals.get("MOUSE_BUTTON_RIGHT").toint());
    }
}
