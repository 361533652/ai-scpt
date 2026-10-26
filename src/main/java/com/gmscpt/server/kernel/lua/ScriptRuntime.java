package com.gmscpt.server.kernel.lua;

import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.domain.RunExit;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.kernel.input.InputEvent;
import com.gmscpt.server.kernel.input.InputEventType;
import com.gmscpt.server.kernel.input.InputGateway;
import com.gmscpt.server.kernel.input.InputState;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.runtime.ScriptHost;
import com.gmscpt.server.kernel.runtime.StopReason;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 一个脚本的运行实例：一条专属线程 + 一份独占的 Lua 状态 + 一个有界事件队列。
 *
 * <p>相比旧版的关键差异：
 * <ol>
 *   <li><b>事件派发不依赖 {@code Sleep}</b>。顶层代码跑完后进入独立的派发循环，
 *       按 Logitech 语义持续调用 {@code OnEvent}；{@code Sleep}/{@code PressAndReleaseKey}
 *       期间也会继续泵事件（旧版只有 Sleep 入口泵一次，长按期间事件全堵住）。</li>
 *   <li><b>事件队列有界</b>（默认 4096），满了丢最旧并计数——旧版是无界队列，脚本不睡就 OOM。</li>
 *   <li><b>停止是真的能停</b>：{@code debug.sethook} 指令钩子（沙箱换成 {@code debugGlobals()}
 *       之后才真的装上）+ 线程中断 + 宽限期判 KILLED 三级阶梯。</li>
 *   <li><b>结束必定松开自己注入的按键</b>，不会出现「脚本停了 W 键还按着」。</li>
 * </ol>
 */
public final class ScriptRuntime implements ScriptHost, HostContext {

    private static final Logger log = LoggerFactory.getLogger(ScriptRuntime.class);

    /** 一次 pump 最多处理多少事件，避免长时间不让出 Lua 执行权。 */
    private static final int PUMP_BATCH = 32;
    /** 指令钩子间隔：每 N 条指令检查一次停止标志与队列。 */
    private static final int HOOK_INTERVAL = 5000;
    /** OnEvent 连续报错多少次后判定脚本不可用并停止。 */
    private static final int MAX_CONSECUTIVE_ERRORS = 50;
    private static final long DROP_WARN_EVERY = 200;
    /** 停止信号的统一文案：指令钩子、阻塞 API、中断路径都用它，便于 {@link #isStopSignal} 识别。 */
    static final String STOP_MESSAGE = "脚本已被请求停止";

    private final Script script;
    private final String runId;
    private final String trigger;
    private final int queueCapacity;
    private final long stopGraceMs;
    private final long maxRunMs;
    private final LogBus logBus;
    private final InputGateway gateway;
    private final InputState inputState;
    private final Consumer<ScriptRuntime> onFinished;

    private final LinkedBlockingDeque<InputEvent> queue;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger actionCount = new AtomicInteger();
    private final CountDownLatch done = new CountDownLatch(1);

    private volatile StopReason stopReason;
    private volatile RunExit exit;
    private volatile String errorMessage;
    private volatile Thread thread;
    private volatile Globals globals;
    private volatile LuaValue onEvent;
    private volatile boolean pumping;
    private volatile boolean abandoned;
    private volatile long runStartMillis;
    private int consecutiveErrors;

    public ScriptRuntime(Script script,
                         String runId,
                         String trigger,
                         GmScriptProperties.RuntimeSettings settings,
                         LogBus logBus,
                         InputGateway gateway,
                         InputState inputState,
                         Consumer<ScriptRuntime> onFinished) {
        this.script = script;
        this.runId = runId;
        this.trigger = trigger;
        this.queueCapacity = Math.max(64, settings.getEventQueueCapacity());
        this.stopGraceMs = settings.getStopGraceMs();
        this.maxRunMs = settings.getMaxRunMs();
        this.logBus = logBus;
        this.gateway = gateway;
        this.inputState = inputState;
        this.onFinished = onFinished;
        this.queue = new LinkedBlockingDeque<>(queueCapacity);
    }

    // ---------------- 生命周期 ----------------

    public void start() {
        Thread t = new Thread(this::runScript, "gmscpt-script-" + script.id());
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, ex) -> log.error("脚本线程未捕获异常 {}", th.getName(), ex));
        this.thread = t;
        logBus.publish(LogLevel.INFO, "runtime", script.id(), runId,
                "运行开始：" + script.name() + "（触发方式：" + trigger + "）");
        t.start();
    }

    private void runScript() {
        runStartMillis = System.currentTimeMillis();
        try {
            globals = LuaSandbox.create();
            LuaApiRegistry api = LuaApiRegistry.create(this);
            api.registerAll(globals);
            installInstructionHook();

            LuaValue chunk = globals.load(script.source(), "@" + script.name());
            chunk.call();

            onEvent = globals.get("OnEvent");
            if (onEvent.isnil()) {
                logBus.publish(LogLevel.INFO, "runtime", script.id(), runId,
                        "顶层代码执行完毕（未定义 OnEvent），本次运行结束");
                exit = RunExit.OK;
                return;
            }
            logBus.publish(LogLevel.INFO, "runtime", script.id(), runId, "已进入事件派发循环");
            dispatchLoop();
        } catch (StoppedException se) {
            exit = exitForStop();
            errorMessage = stopReason == null ? null : ("停止原因：" + stopReason);
        } catch (LuaError le) {
            if (isStopSignal(le)) {
                // 指令钩子里抛出的 StoppedException 会被 LuaJ 包成 LuaError，
                // 这里必须把它认回「停止」，否则死循环被停下来时会被误记成 LUA_ERROR。
                exit = exitForStop();
                errorMessage = stopReason == null ? null : ("停止原因：" + stopReason);
            } else {
                exit = RunExit.LUA_ERROR;
                errorMessage = le.getMessage();
                logBus.publish(LogLevel.ERROR, "runtime", script.id(), runId, "Lua 错误：" + le.getMessage());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            exit = exitForStop();
        } catch (Throwable t) {
            exit = RunExit.LUA_ERROR;
            errorMessage = t.toString();
            logBus.publish(LogLevel.ERROR, "runtime", script.id(), runId, "运行时异常：" + t);
            log.error("脚本运行异常", t);
        } finally {
            finish();
        }
    }

    /** 顶层执行完之后的事件派发循环——这是与旧版最大的行为差异。 */
    private void dispatchLoop() throws InterruptedException {
        while (!stopRequested.get()) {
            InputEvent event = queue.pollFirst(20, TimeUnit.MILLISECONDS);
            if (event != null) {
                eventCount.incrementAndGet();
                dispatchToOnEvent(event);
            }
            if (!queue.isEmpty()) {
                pump();
            }
            if (maxRunMs > 0 && System.currentTimeMillis() - runStartMillis > maxRunMs) {
                stopReason = StopReason.TIMEOUT;
                stopRequested.set(true);
                logBus.publish(LogLevel.WARN, "runtime", script.id(), runId,
                        "已超过最长运行时间 " + maxRunMs + " ms，主动停止");
                throw new StoppedException("超过最长运行时间");
            }
        }
    }

    private void dispatchToOnEvent(InputEvent event) {
        LuaValue fn = onEvent;
        if (fn == null || fn.isnil()) return;
        try {
            LuaValue arg = event.isMouse() ? LuaValue.valueOf(event.button()) : LuaValue.NIL;
            fn.call(LuaValue.valueOf(event.type().code()), arg);
            consecutiveErrors = 0;
        } catch (LuaError le) {
            if (isStopSignal(le)) {
                throw new StoppedException("脚本已被请求停止");
            }
            consecutiveErrors++;
            logBus.publish(LogLevel.WARN, "runtime", script.id(), runId,
                    "OnEvent 报错（第 " + consecutiveErrors + " 次）：" + le.getMessage());
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                exit = RunExit.LUA_ERROR;
                errorMessage = "OnEvent 连续报错 " + consecutiveErrors + " 次";
                throw new StoppedException(errorMessage);
            }
        }
    }

    /**
     * 装指令钩子（F1 修复的核心）。
     *
     * <p>没有它，{@code while true do end} 这种不含任何阻塞 API 的死循环既收不到事件、
     * 也永远看不到停止标志，只能靠杀进程。
     */
    private void installInstructionHook() {
        LuaValue debug = globals.get("debug");
        if (debug.isnil()) {
            logBus.publish(LogLevel.ERROR, "runtime", script.id(), runId,
                    "debug 库不可用，死循环将无法被中断（沙箱配置有误）");
            return;
        }
        LuaValue sethook = debug.get("sethook");
        if (sethook.isnil()) {
            logBus.publish(LogLevel.ERROR, "runtime", script.id(), runId, "debug.sethook 不可用");
            return;
        }
        VarArgFunction hook = new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (stopRequested.get()) {
                    throw new StoppedException("脚本已被请求停止");
                }
                if (!queue.isEmpty()) {
                    pump();
                }
                return LuaValue.NONE;
            }
        };
        try {
            sethook.invoke(LuaValue.varargsOf(hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_INTERVAL)));
        } catch (Throwable t) {
            logBus.publish(LogLevel.ERROR, "runtime", script.id(), runId, "安装指令钩子失败：" + t);
        }
    }

    private void finish() {
        if (abandoned) return;
        alive.set(false);
        try {
            gateway.releaseAll();
        } catch (Throwable t) {
            log.warn("释放残留输入失败: {}", t.toString());
        }
        RunExit e = exit == null ? RunExit.OK : exit;
        String msg = "运行结束：" + e + (errorMessage == null ? "" : "（" + errorMessage + "）")
                + "，事件 " + eventCount.get() + "，动作 " + actionCount.get()
                + (dropped.get() > 0 ? "，丢弃事件 " + dropped.get() : "");
        logBus.publish(e == RunExit.OK || e == RunExit.STOPPED ? LogLevel.INFO : LogLevel.WARN,
                "runtime", script.id(), runId, msg);
        onEvent = null;
        globals = null;
        done.countDown();
        Consumer<ScriptRuntime> cb = onFinished;
        if (cb != null) {
            try {
                cb.accept(this);
            } catch (Throwable t) {
                log.warn("运行结束回调异常: {}", t.toString());
            }
        }
    }

    private RunExit exitForStop() {
        if (abandoned) return RunExit.KILLED;
        StopReason r = stopReason;
        if (r == null) return RunExit.STOPPED;
        return switch (r) {
            case TIMEOUT -> RunExit.TIMEOUT;
            default -> RunExit.STOPPED;
        };
    }

    private boolean isStopSignal(LuaError le) {
        // 沿 cause 链查（LuaJ 会把 hook 里抛出的异常包进 LuaError），
        // 同时兜底看消息文本——包了几层之后 cause 未必还在。
        Throwable t = le;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof StoppedException) return true;
            String m = t.getMessage();
            if (m != null && m.contains(STOP_MESSAGE)) return true;
            t = t.getCause();
        }
        return false;
    }

    // ---------------- ScriptHost ----------------

    @Override
    public String scriptId() {
        return script.id();
    }

    @Override
    public String scriptName() {
        return script.name();
    }

    @Override
    public String runId() {
        return runId;
    }

    @Override
    public String trigger() {
        return trigger;
    }

    @Override
    public boolean isAlive() {
        return alive.get();
    }

    @Override
    public void offer(InputEvent event) {
        if (!alive.get()) return;
        if (queue.offerLast(event)) return;
        // 满了：丢最旧，保留最新（事件的新鲜度比完整性更重要）
        queue.pollFirst();
        int n = dropped.incrementAndGet();
        if (!queue.offerLast(event)) {
            dropped.incrementAndGet();
        }
        if (n % DROP_WARN_EVERY == 1) {
            logBus.publish(LogLevel.WARN, "runtime", script.id(), runId,
                    "事件队列已满（容量 " + queueCapacity + "），累计丢弃 " + dropped.get()
                            + " 条；脚本可能在长时间不让出执行权");
        }
    }

    @Override
    public void requestStop(StopReason reason) {
        if (stopRequested.compareAndSet(false, true)) {
            this.stopReason = reason;
            logBus.publish(LogLevel.INFO, "runtime", script.id(), runId, "已请求停止（" + reason + "）");
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean awaitStop(long millis) throws InterruptedException {
        return done.await(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int droppedEvents() {
        return dropped.get();
    }

    @Override
    public int eventCount() {
        return eventCount.get();
    }

    @Override
    public int actionCount() {
        return actionCount.get();
    }

    @Override
    public void forceAbandon(String reason) {
        abandoned = true;
        exit = RunExit.KILLED;
        errorMessage = reason;
        alive.set(false);
        try {
            gateway.releaseAll();
        } catch (Throwable t) {
            log.warn("强制弃用后释放输入失败: {}", t.toString());
        }
        // 断开引用，避免继续持有可能已损坏的 Lua 状态
        onEvent = null;
        globals = null;
        done.countDown();
        logBus.publish(LogLevel.ERROR, "runtime", script.id(), runId,
                "停止超时（>" + stopGraceMs + " ms），已判定 KILLED 并弃用该 Lua 状态：" + reason);
    }

    @Override
    public RunExit exit() {
        return exit;
    }

    /** 失败原因（正常结束时为 null）。 */
    public String errorMessage() {
        return errorMessage;
    }

    public long stopGraceMs() {
        return stopGraceMs;
    }

    public long startedAtMillis() {
        return runStartMillis;
    }

    public StopReason stopReason() {
        return stopReason;
    }

    public int queueDepth() {
        return queue.size();
    }

    // ---------------- HostContext ----------------

    @Override
    public InputGateway gateway() {
        return gateway;
    }

    @Override
    public InputState inputState() {
        return inputState;
    }

    @Override
    public long runStartMillis() {
        return runStartMillis;
    }

    @Override
    public void log(String message) {
        logBus.publish(LogLevel.INFO, "script", script.id(), runId, message);
    }

    @Override
    public void incAction() {
        actionCount.incrementAndGet();
    }

    @Override
    public void checkStop() {
        if (stopRequested.get()) {
            throw new StoppedException("脚本已被请求停止");
        }
    }

    @Override
    public void sleepWithPump(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            checkStop();
            pump();
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) return;
            Thread.sleep(Math.min(remainingMs, 5L));
        }
    }

    @Override
    public void pump() {
        if (Thread.currentThread() != thread) {
            return; // 只有脚本线程可以触碰 Lua 状态
        }
        // OnEvent 还没绑定（顶层代码还在跑）时**不能**把事件取出来：
        // 取出来就再也放不回去，等于静默丢事件。让它们留在队列里等派发循环接手。
        // 这条是本类第一版的 bug，由 LuaRuntimeTest#eventsAreDispatchedDuringSleep 抓到。
        if (onEvent == null || onEvent.isnil()) {
            return;
        }
        if (pumping) {
            return; // 防重入：OnEvent 里再调 Sleep 不会递归派发
        }
        pumping = true;
        try {
            for (int i = 0; i < PUMP_BATCH && !stopRequested.get(); i++) {
                InputEvent e = queue.pollFirst();
                if (e == null) break;
                eventCount.incrementAndGet();
                dispatchToOnEvent(e);
            }
        } finally {
            pumping = false;
        }
    }
}
