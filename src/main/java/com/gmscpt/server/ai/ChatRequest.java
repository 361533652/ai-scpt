package com.gmscpt.server.ai;

import com.gmscpt.server.domain.ChatMessage;

import java.util.List;

/**
 * 一次对话请求。
 *
 * @param sessionId   会话 id（用于落库与「同一会话单飞」）
 * @param providerId  通道 id：{@code ollama} 或 {@code openai-compat}（或设置里自定义的 id）
 * @param model       模型名；null 表示用通道默认
 * @param systemPrompt 系统提示词（由 PromptBuilder 组装，含 Lua API 白名单与知识片段）
 * @param history     历史消息（已按预算裁剪）
 * @param userMessage 本轮用户输入
 * @param temperature 采样温度
 */
public record ChatRequest(
        String sessionId,
        String providerId,
        String model,
        String systemPrompt,
        List<ChatMessage> history,
        String userMessage,
        Double temperature
) {
}
