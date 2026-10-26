package com.gmscpt.server.api;

import com.gmscpt.server.domain.LogEntry;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.domain.RunExit;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.runtime.RuntimeListener;
import com.gmscpt.server.kernel.runtime.RuntimeManager;
import com.gmscpt.server.kernel.runtime.ScriptHost;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志接口：历史查询 + SSE 实时流。
 *
 * <p>旧版日志只写进 Swing 组件，既不落盘也不能导出，打包后出错完全看不见。
 * 这里日志同时进内存环形缓冲（实时推送）、SQLite（可查）与前端控制台。
 */
@RestController
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private final LogBus logBus;
    private final RuntimeManager runtimeManager;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gmscpt-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public LogController(LogBus logBus, RuntimeManager runtimeManager) {
        this.logBus = logBus;
        this.runtimeManager = runtimeManager;
    }

    @GetMapping("/api/logs")
    public Map<String, Object> history(@RequestParam(required = false) String scriptId,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(required = false) Long sinceSeq,
                                       @RequestParam(defaultValue = "500") int limit) {
        List<LogEntry> items = sinceSeq != null
                ? logBus.since(sinceSeq, limit)
                : filter(logBus.recent(limit), scriptId, level);
        return Map.of("items", items, "lastSeq", logBus.lastSeq());
    }

    private List<LogEntry> filter(List<LogEntry> entries, String scriptId, String level) {
        if ((scriptId == null || scriptId.isBlank()) && (level == null || level.isBlank())) {
            return entries;
        }
        LogLevel min = level == null || level.isBlank() ? null : LogLevel.valueOf(level.toUpperCase(java.util.Locale.ROOT));
        return entries.stream()
                .filter(e -> scriptId == null || scriptId.isBlank() || scriptId.equals(e.scriptId()))
                .filter(e -> min == null || e.level().ordinal() >= min.ordinal())
                .toList();
    }

    /**
     * SSE：{@code event: log} 推送日志，{@code event: run} 推送运行态变化，每 15 秒一行心跳注释。
     * 超时设为 0（不主动断开），由连接方关闭；同时靠心跳维持中间设备的连接。
     */
    @GetMapping(value = "/api/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) Long sinceSeq) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        try {
            List<LogEntry> backlog = sinceSeq == null
                    ? logBus.recent(200)
                    : logBus.since(sinceSeq, 500);
            for (LogEntry e : backlog) {
                emitter.send(SseEmitter.event().id(String.valueOf(e.seq())).name("log").data(e));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        AutoCloseable subscription = logBus.subscribe(entry -> {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(entry.seq())).name("log").data(entry));
            } catch (Exception e) {
                closed.set(true);
                emitter.complete();
            }
        });

        RuntimeListener runListener = new RuntimeListener() {
            @Override
            public void onStarted(ScriptHost host) {
                sendRun(host, null);
            }

            @Override
            public void onFinished(ScriptHost host, RunExit exit, String errorMessage) {
                sendRun(host, exit);
            }

            private void sendRun(ScriptHost host, RunExit exit) {
                if (closed.get()) return;
                try {
                    emitter.send(SseEmitter.event().name("run").data(Map.of(
                            "scriptId", host.scriptId(),
                            "scriptName", host.scriptName(),
                            "runId", host.runId(),
                            "status", exit == null ? "RUNNING" : exit.name(),
                            "eventCount", host.eventCount(),
                            "actionCount", host.actionCount(),
                            "droppedEvents", host.droppedEvents())));
                } catch (Exception e) {
                    closed.set(true);
                    emitter.complete();
                }
            }
        };
        runtimeManager.addListener(runListener);

        var heartbeatTask = heartbeat.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                closed.set(true);
                emitter.complete();
            }
        }, 15, 15, TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                heartbeatTask.cancel(false);
                runtimeManager.removeListener(runListener);
                try {
                    subscription.close();
                } catch (Exception e) {
                    log.debug("关闭日志订阅失败: {}", e.toString());
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }
}
