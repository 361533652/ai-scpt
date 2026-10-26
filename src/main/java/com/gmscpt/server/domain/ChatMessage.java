package com.gmscpt.server.domain;

import java.time.Instant;

/**
 * 一条对话消息。{@code thinking} 单独存（qwen3 的思考链），界面上可折叠，不混进正文。
 */
public record ChatMessage(
        String id,
        String sessionId,
        ChatRole role,
        String content,
        String thinking,
        int tokensIn,
        int tokensOut,
        String providerId,
        String model,
        Instant createdAt
) {
    public ChatMessage {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
        if (role == null) role = ChatRole.USER;
        if (content == null) content = "";
        if (createdAt == null) createdAt = Instant.now();
    }
}
