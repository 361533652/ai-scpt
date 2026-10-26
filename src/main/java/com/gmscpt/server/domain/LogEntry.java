package com.gmscpt.server.domain;

/**
 * 一条结构化日志。既进内存环形缓冲（SSE 实时推送），也异步落 SQLite。
 *
 * @param seq 单调递增序号，用作 SSE 的 {@code id:}，支持断线续传
 */
public record LogEntry(
        long seq,
        long timeMillis,
        LogLevel level,
        String source,
        String scriptId,
        String runId,
        String message
) {
    public LogEntry {
        if (level == null) level = LogLevel.INFO;
        if (source == null) source = "app";
        if (message == null) message = "";
    }
}
