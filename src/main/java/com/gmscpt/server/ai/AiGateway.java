package com.gmscpt.server.ai;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 通道抽象：本地 Ollama 与任意 OpenAI 兼容端点共用。
 *
 * <p>返回 Reactor {@link Flux} 而不是回调：取消语义天然由 {@code Disposable.dispose()} 表达，
 * 旧版是「SSE 无限读超时 + 没有取消按钮」，一旦上游挂起聊天框就永久锁死。
 */
public interface AiGateway {

    /** 通道 id，如 {@code ollama} / {@code openai-compat}。 */
    String id();

    /** 通道当前是否可用（探活结果，带短缓存）。 */
    boolean available();

    /** 该通道下的模型列表（Ollama 走 /api/tags，OpenAI 兼容走 /v1/models）。 */
    List<String> models();

    /** 流式对话。返回的 Flux 被 dispose 时必须立刻中止上游请求。 */
    Flux<AiChunk> stream(ChatRequest request);

    /** 通道元信息，供 {@code GET /api/ai/providers}。 */
    default AiProviderInfo info() {
        return new AiProviderInfo(id(), available(), models());
    }
}
