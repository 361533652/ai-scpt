package com.gmscpt.server.api;

import com.gmscpt.server.domain.LogEntry;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.log.RingLogBus;
import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.kernel.runtime.RuntimeManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SSE 日志流的回归测试。
 *
 * <p>对应一个真实踩到的 bug：当客户端带上 {@code sinceSeq} 且当前没有新日志时，
 * 后端一个事件都不发，Tomcat 于是不 flush 响应头，浏览器的 {@code fetch}
 * 永远拿不到 response，直到前端 15 秒建连超时——报「连接 /logs/stream 超时」。
 *
 * <p>迷惑之处：第一次打开页面是正常的（有历史日志可发），只有重连时才炸，
 * 所以很容易被误判成「后端没启动」。
 */
class LogStreamFlushTest {

    /** 记录 emitter 实际发出了什么，避免真的起 HTTP 服务。 */
    private static final class RecordingLogBus implements LogBus {
        int published;
        private final java.util.List<LogEntry> entries = new java.util.ArrayList<>();

        void seed(int n) {
            for (int i = 0; i < n; i++) {
                entries.add(new LogEntry(i + 1L, System.currentTimeMillis(), LogLevel.INFO,
                        "test", null, null, "line-" + i));
            }
        }

        @Override
        public void publish(LogLevel level, String source, String scriptId, String runId, String message) {
            published++;
        }

        @Override
        public List<LogEntry> recent(int limit) {
            int n = Math.min(limit, entries.size());
            return entries.subList(Math.max(0, entries.size() - n), entries.size());
        }

        @Override
        public List<LogEntry> since(long seq, int limit) {
            return entries.stream().filter(e -> e.seq() > seq).limit(limit).toList();
        }

        @Override
        public long lastSeq() {
            return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
        }

        @Override
        public AutoCloseable subscribe(Consumer<LogEntry> consumer) {
            return () -> {
            };
        }
    }

    private static RuntimeManager noRuntime() {
        // 日志流只用到 addListener/removeListener，用匿名实现避免拉入整套运行期依赖
        return new RuntimeManager() {
            @Override
            public com.gmscpt.server.kernel.runtime.ScriptHost start(
                    com.gmscpt.server.domain.Script script, String trigger) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<com.gmscpt.server.kernel.runtime.ScriptHost> find(String scriptId) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean isRunning(String scriptId) {
                return false;
            }

            @Override
            public List<com.gmscpt.server.kernel.runtime.ScriptHost> running() {
                return List.of();
            }

            @Override
            public void stop(String scriptId, com.gmscpt.server.kernel.runtime.StopReason reason) {
            }

            @Override
            public void stopAll(com.gmscpt.server.kernel.runtime.StopReason reason) {
            }

            @Override
            public void addListener(com.gmscpt.server.kernel.runtime.RuntimeListener listener) {
            }

            @Override
            public void removeListener(com.gmscpt.server.kernel.runtime.RuntimeListener listener) {
            }
        };
    }

    @Test
    @DisplayName("有历史日志时能正常建流")
    void streamsWhenBacklogExists() {
        RecordingLogBus bus = new RecordingLogBus();
        bus.seed(3);
        LogController controller = new LogController(bus, noRuntime());

        SseEmitter emitter = assertDoesNotThrow(() -> controller.stream(null));
        assertNotNull(emitter);
        assertDoesNotThrow(() -> emitter.complete());
    }

    @Test
    @DisplayName("sinceSeq 超过最新序号（空 backlog）时也不能挂住——这正是超时 bug 的场景")
    void doesNotHangWhenBacklogIsEmpty() {
        RecordingLogBus bus = new RecordingLogBus();
        bus.seed(3);
        LogController controller = new LogController(bus, noRuntime());

        // 客户端说「我已经看到第 999999 条了」，于是没有新日志可发。
        // 修复前这里不会 send 任何东西，响应头永不 flush，浏览器 15 秒超时。
        SseEmitter emitter = assertDoesNotThrow(() -> controller.stream(999_999L));
        assertNotNull(emitter);
        assertDoesNotThrow(() -> emitter.complete());
    }

    @Test
    @DisplayName("空日志库 + sinceSeq 也必须立刻建流")
    void doesNotHangOnEmptyLogBus() {
        RecordingLogBus bus = new RecordingLogBus();
        LogController controller = new LogController(bus, noRuntime());

        SseEmitter emitter = assertDoesNotThrow(() -> controller.stream(0L));
        assertNotNull(emitter);
        assertDoesNotThrow(() -> emitter.complete());
    }

    @Test
    @DisplayName("RingLogBus 的 since 语义：只返回比给定序号更大的条目")
    void ringBusSinceSemantics() {
        RingLogBus bus = new RingLogBus(null, new GmScriptProperties());
        for (int i = 0; i < 5; i++) {
            bus.info("test", "m" + i);
        }
        long last = bus.lastSeq();
        assertDoesNotThrow(() -> bus.since(last, 100));
        // 用最新序号去取，结果必须为空（这正是触发 bug 的前置条件）
        org.junit.jupiter.api.Assertions.assertTrue(bus.since(last, 100).isEmpty(),
                "since(最新序号) 应为空，这是 SSE 空 backlog 场景的来源");
        org.junit.jupiter.api.Assertions.assertFalse(bus.since(last - 2, 100).isEmpty());
    }
}
