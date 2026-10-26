package com.gmscpt.server.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考链开关的回归测试。
 *
 * <p>背景（真机踩到的坑）：对 {@code qwen2.5-coder:7b} 下发 {@code think:true}，
 * Ollama 直接返回 {@code 400 {"error":"... does not support thinking"}}。
 * 所以「默认模型」在三通道里能不能用，取决于这里判得对不对。
 */
class OllamaThinkingTest {

    @Test
    @DisplayName("推理模型才开 think，普通模型一律关")
    void onlyReasoningModelsEnableThinking() {
        assertTrue(OllamaAiGateway.supportsThinking("qwen3:8b"));
        assertTrue(OllamaAiGateway.supportsThinking("qwen3:30b-a3b"));
        assertTrue(OllamaAiGateway.supportsThinking("deepseek-r1:7b"));
        assertTrue(OllamaAiGateway.supportsThinking("gpt-oss:20b"));

        // 实测会 400 的模型
        assertFalse(OllamaAiGateway.supportsThinking("qwen2.5-coder:7b"));
        assertFalse(OllamaAiGateway.supportsThinking("gemma3:4b"));
        assertFalse(OllamaAiGateway.supportsThinking("nomic-embed-text:latest"));
        assertFalse(OllamaAiGateway.supportsThinking("llama3.2:3b"));
        assertFalse(OllamaAiGateway.supportsThinking(null));
    }

    @Test
    @DisplayName("带命名空间的模型名也要认出来")
    void handlesNamespacedModels() {
        assertTrue(OllamaAiGateway.supportsThinking("library/qwen3:8b"));
        assertTrue(OllamaAiGateway.supportsThinking("registry.example.com/qwen3:14b"));
    }
}
