package com.gmscpt.server.ai;

/**
 * AI 通道的统一异常。
 *
 * <p>为什么要包一层：上层（SSE 控制器）需要稳定的、能直接展示给用户的中文原因与一个通道 id，
 * 而不是把 {@code WebClientRequestException}、{@code TimeoutException} 这类内部类型透给前端。
 * 旧版直接把上游异常文本打到聊天框里，用户看到的是堆栈首行。
 *
 * <p>{@code cause} 保留原始异常，日志里仍然打得出根因。
 */
public class AiGatewayException extends RuntimeException {

    private final String providerId;

    public AiGatewayException(String providerId, Throwable cause, String message) {
        super(message, cause);
        this.providerId = providerId;
    }

    /** 出错的通道 id。 */
    public String providerId() {
        return providerId;
    }
}
