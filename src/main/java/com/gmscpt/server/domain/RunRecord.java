package com.gmscpt.server.domain;

import java.time.Instant;

/**
 * 一次脚本运行的记录。{@code runId} 同时作为输入注入的归属标记（回声抑制用），
 * 因此必须是全局唯一且不可复用的字符串。
 */
public record RunRecord(
        String id,
        String scriptId,
        String trigger,
        Instant startedAt,
        Instant endedAt,
        RunExit exit,
        String errorMessage,
        int eventCount,
        int actionCount,
        int droppedEvents
) {
    public RunRecord {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        if (startedAt == null) startedAt = Instant.now();
    }
}
