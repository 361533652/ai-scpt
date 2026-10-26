package com.gmscpt.server.kernel.runtime;

import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.domain.RunExit;
import com.gmscpt.server.domain.RunRecord;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.kernel.input.InputGateway;
import com.gmscpt.server.kernel.input.InputState;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.lua.ScriptRuntime;
import com.gmscpt.server.store.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 运行期管理器的默认实现。
 *
 * <p>旧版 {@code MainFrame.runScript()} 直接把 {@code currentEngine} 覆盖掉：
 * 不 join 旧线程、不从派发器注销，于是「再点一次运行」会留下一个还在收事件的老引擎。
 * 这里所有启停都收口在一处，并且停止带**宽限期看门狗**：超时未停就判 KILLED。
 */
public class RuntimeManagerImpl implements RuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(RuntimeManagerImpl.class);

    private final GmScriptProperties props;
    private final LogBus logBus;
    private final InputGateway gateway;
    private final InputState inputState;
    private final RunRepository runRepository;

    private final Map<String, ScriptRuntime> running = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RuntimeListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService watchdog =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gmscpt-stop-watchdog");
                t.setDaemon(true);
                return t;
            });

    public RuntimeManagerImpl(GmScriptProperties props,
                              LogBus logBus,
                              InputGateway gateway,
                              InputState inputState,
                              RunRepository runRepository) {
        this.props = props;
        this.logBus = logBus;
        this.gateway = gateway;
        this.inputState = inputState;
        this.runRepository = runRepository;
    }

    @Override
    public ScriptHost start(Script script, String trigger) {
        ScriptRuntime existing = running.get(script.id());
        if (existing != null && existing.isAlive()) {
            logBus.publish(LogLevel.WARN, "runtime", script.id(), existing.runId(),
                    "脚本已在运行中，忽略重复启动请求");
            return existing;
        }
        String runId = UUID.randomUUID().toString();
        RunRecord record = new RunRecord(runId, script.id(), trigger, Instant.now(), null, null, null, 0, 0, 0);
        if (runRepository != null) {
            try {
                runRepository.insert(record);
            } catch (Throwable t) {
                log.warn("运行记录落库失败: {}", t.toString());
            }
        }
        ScriptRuntime runtime = new ScriptRuntime(script, runId, trigger,
                props.getRuntime(), logBus, gateway, inputState, this::onFinished);
        running.put(script.id(), runtime);
        for (RuntimeListener l : listeners) {
            try {
                l.onStarted(runtime);
            } catch (Throwable t) {
                log.warn("运行开始回调异常: {}", t.toString());
            }
        }
        runtime.start();
        return runtime;
    }

    private void onFinished(ScriptRuntime runtime) {
        running.remove(runtime.scriptId(), runtime);
        RunExit exit = runtime.exit() == null ? RunExit.OK : runtime.exit();
        RunRecord finished = new RunRecord(runtime.runId(), runtime.scriptId(), runtime.trigger(),
                Instant.ofEpochMilli(runtime.startedAtMillis()), Instant.now(), exit,
                runtime.errorMessage(), runtime.eventCount(),
                runtime.actionCount(), runtime.droppedEvents());
        if (runRepository != null) {
            try {
                runRepository.finish(finished);
            } catch (Throwable t) {
                log.warn("运行记录更新失败: {}", t.toString());
            }
        }
        for (RuntimeListener l : listeners) {
            try {
                l.onFinished(runtime, exit, null);
            } catch (Throwable t) {
                log.warn("运行结束回调异常: {}", t.toString());
            }
        }
    }

    @Override
    public Optional<ScriptHost> find(String scriptId) {
        return Optional.ofNullable(running.get(scriptId));
    }

    @Override
    public boolean isRunning(String scriptId) {
        ScriptRuntime r = running.get(scriptId);
        return r != null && r.isAlive();
    }

    @Override
    public List<ScriptHost> running() {
        List<ScriptHost> out = new ArrayList<>(running.size());
        for (ScriptRuntime r : running.values()) {
            if (r.isAlive()) out.add(r);
        }
        return out;
    }

    @Override
    public void stop(String scriptId, StopReason reason) {
        ScriptRuntime runtime = running.get(scriptId);
        if (runtime == null) return;
        runtime.requestStop(reason);
        long grace = runtime.stopGraceMs();
        watchdog.submit(() -> {
            try {
                if (!runtime.awaitStop(grace)) {
                    runtime.forceAbandon("超过停止宽限期 " + grace + " ms 仍在运行");
                    running.remove(scriptId, runtime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void stopAll(StopReason reason) {
        for (String id : new ArrayList<>(running.keySet())) {
            stop(id, reason);
        }
    }

    @Override
    public void addListener(RuntimeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(RuntimeListener listener) {
        listeners.remove(listener);
    }

    /** 进程退出前的收尾：停全部脚本、释放所有注入的输入。 */
    public void shutdown() {
        stopAll(StopReason.SHUTDOWN);
        try {
            watchdog.shutdown();
            watchdog.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            gateway.releaseAll();
        } catch (Throwable t) {
            log.warn("关停时释放输入失败: {}", t.toString());
        }
    }
}
