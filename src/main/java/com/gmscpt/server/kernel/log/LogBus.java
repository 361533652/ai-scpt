package com.gmscpt.server.kernel.log;

import com.gmscpt.server.domain.LogEntry;
import com.gmscpt.server.domain.LogLevel;

import java.util.List;
import java.util.function.Consumer;

/**
 * 日志总线：内存环形缓冲 + 订阅推送（SSE），并异步落库。
 *
 * <p>旧版日志只进 Swing 组件（上限 5000 行），既不落盘也不可导出，
 * 打包成 exe 后出错完全看不见。新设计里日志是一等公民。
 */
public interface LogBus {

    void publish(LogLevel level, String source, String scriptId, String runId, String message);

    default void info(String source, String message) {
        publish(LogLevel.INFO, source, null, null, message);
    }

    default void warn(String source, String message) {
        publish(LogLevel.WARN, source, null, null, message);
    }

    default void error(String source, String message) {
        publish(LogLevel.ERROR, source, null, null, message);
    }

    default void error(String source, String message, Throwable t) {
        publish(LogLevel.ERROR, source, null, null, message + ": " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : " " + t.getMessage()));
    }

    /** 最近 n 条（时间正序）。 */
    List<LogEntry> recent(int limit);

    /** 取 seq 大于给定值的日志（断线续传用）。 */
    List<LogEntry> since(long seq, int limit);

    long lastSeq();

    /** 订阅实时日志；关闭返回值即退订。 */
    AutoCloseable subscribe(Consumer<LogEntry> consumer);
}
