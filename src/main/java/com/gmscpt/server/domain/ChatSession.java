package com.gmscpt.server.domain;

import java.time.Instant;

/** AI 对话会话。{@code providerId} 记录该会话当时用的是哪条通道，便于回溯。 */
public record ChatSession(
        String id,
        String title,
        String providerId,
        String model,
        Instant createdAt
) {
    public ChatSession {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("session id 不能为空");
        if (title == null || title.isBlank()) title = "新对话";
        if (createdAt == null) createdAt = Instant.now();
    }
}
