package com.gmscpt.server.domain;

/**
 * AI 通道类型。决定要不要发鉴权头、走哪套流式协议细节。
 * 旧版对本地 Ollama 也硬发 {@code Authorization: Bearer }（空 key），是隐藏故障源之一。
 */
public enum ProviderKind {
    /** 本机 Ollama（默认，不需要 apiKey） */
    OLLAMA,
    /** 任意 OpenAI 兼容端点（云端 API、或 Ollama 的 /v1 兼容层） */
    OPENAI_COMPAT
}
