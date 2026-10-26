package com.gmscpt.server.ai;

/**
 * 流式输出的一帧，两条通道（Ollama 原生 / OpenAI 兼容）都翻译成这个统一形状，
 * 前端只认这一套（旧版让前端直接解析某一家厂商的 SSE 字段，换模型就得改前端）。
 *
 * @param channel   通道：{@code content} 或 {@code thinking}
 * @param text      增量文本
 * @param tokensIn  仅最后一帧带（usage）
 * @param tokensOut 仅最后一帧带（usage）
 * @param done      是否结束
 * @param finishReason 结束原因（stop / length / cancelled / error）
 */
public record AiChunk(
        String channel,
        String text,
        Integer tokensIn,
        Integer tokensOut,
        boolean done,
        String finishReason
) {

    public static AiChunk content(String text) {
        return new AiChunk("content", text, null, null, false, null);
    }

    public static AiChunk thinking(String text) {
        return new AiChunk("thinking", text, null, null, false, null);
    }

    public static AiChunk usage(Integer in, Integer out) {
        return new AiChunk(null, null, in, out, false, null);
    }

    public static AiChunk done(String reason) {
        return new AiChunk(null, null, null, null, true, reason);
    }
}
