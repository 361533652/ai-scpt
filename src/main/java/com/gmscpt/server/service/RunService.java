package com.gmscpt.server.service;

import com.gmscpt.server.domain.HotkeyAction;
import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.domain.ScriptStatus;
import com.gmscpt.server.kernel.input.HotkeyHandler;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.runtime.RuntimeManager;
import com.gmscpt.server.kernel.runtime.ScriptHost;
import com.gmscpt.server.kernel.runtime.StopReason;
import com.gmscpt.server.store.ScriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 运行控制用例，同时是热键的执行端。
 *
 * <p>热键回调跑在 JNativeHook 的派发线程上，那里**一毫秒都不能多做**，
 * 否则 Windows 会把钩子摘掉。所以这里用一条单线程队列把「启动/停止脚本」的活儿挪走，
 * 钩子线程只负责投递。
 */
public class RunService implements HotkeyHandler {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    /** 一次运行在界面上的可见状态。 */
    public record ActiveRun(String scriptId, String scriptName, String runId, String trigger,
                            long startedAt, int eventCount, int actionCount, int droppedEvents) {
    }

    private final RuntimeManager runtimeManager;
    private final ScriptRepository scriptRepository;
    private final LogBus logBus;

    private final ExecutorService triggerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gmscpt-hotkey-trigger");
        t.setDaemon(true);
        return t;
    });

    public RunService(RuntimeManager runtimeManager, ScriptRepository scriptRepository, LogBus logBus) {
        this.runtimeManager = runtimeManager;
        this.scriptRepository = scriptRepository;
        this.logBus = logBus;
    }

    public ScriptHost run(String scriptId, String trigger) {
        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> ServiceException.notFound("脚本不存在: " + scriptId));
        if (script.status() == ScriptStatus.DISABLED) {
            throw ServiceException.conflict("SCRIPT_DISABLED", "脚本已禁用，先启用再运行");
        }
        return runtimeManager.start(script, trigger);
    }

    public boolean stop(String scriptId) {
        if (!runtimeManager.isRunning(scriptId)) {
            return false;
        }
        runtimeManager.stop(scriptId, StopReason.USER);
        return true;
    }

    public int stopAll() {
        List<ScriptHost> hosts = runtimeManager.running();
        runtimeManager.stopAll(StopReason.USER);
        return hosts.size();
    }

    public List<ActiveRun> active() {
        List<ActiveRun> out = new ArrayList<>();
        for (ScriptHost h : runtimeManager.running()) {
            out.add(new ActiveRun(h.scriptId(), h.scriptName(), h.runId(), h.trigger(),
                    h.startedAtMillis(), h.eventCount(), h.actionCount(), h.droppedEvents()));
        }
        return out;
    }

    public boolean isRunning(String scriptId) {
        return runtimeManager.isRunning(scriptId);
    }

    /** 热键命中：必须在钩子线程上立刻返回，因此只做投递。 */
    @Override
    public void onHotkey(HotkeyBinding binding) {
        String scriptId = binding.scriptId();
        HotkeyAction action = binding.action();
        triggerExecutor.submit(() -> {
            try {
                switch (action) {
                    case RUN -> {
                        if (!runtimeManager.isRunning(scriptId)) {
                            run(scriptId, "hotkey:" + binding.accelerator());
                        }
                    }
                    case STOP -> stop(scriptId);
                    case TOGGLE -> {
                        if (runtimeManager.isRunning(scriptId)) {
                            stop(scriptId);
                        } else {
                            run(scriptId, "hotkey:" + binding.accelerator());
                        }
                    }
                }
            } catch (Throwable t) {
                logBus.publish(LogLevel.ERROR, "hotkey", scriptId, null,
                        "热键 " + binding.accelerator() + " 触发失败：" + t.getMessage());
                log.warn("热键触发失败", t);
            }
        });
    }

    public void shutdown() {
        triggerExecutor.shutdown();
    }
}
