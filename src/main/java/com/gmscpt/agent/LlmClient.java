package com.gmscpt.agent;

import java.util.List;

/**
 * Pluggable LLM client interface.
 * Implementations handle the actual HTTP communication with different AI providers.
 */
public interface LlmClient {

    /**
     * Message role constants.
     */
    String ROLE_SYSTEM = "system";
    String ROLE_USER = "user";
    String ROLE_ASSISTANT = "assistant";

    /**
     * A single chat message.
     */
    record ChatMessage(String role, String content) {}

    /**
     * Send messages to the LLM and get a response.
     * @param messages conversation history including system prompt
     * @return the assistant's reply text
     * @throws Exception on network/API errors
     */
    String chat(List<ChatMessage> messages) throws Exception;

    /**
     * Callback interface for streaming responses.
     * Separates thinking process (reasoning_content) from final answer (content).
     */
    interface StreamCallback {
        /** Called for each thinking/reasoning chunk (DeepSeek-R1 etc.) */
        default void onThinking(String chunk) {}
        /** Called for each final response chunk */
        void onContent(String chunk);
    }

    /**
     * Send messages with streaming response (SSE).
     * @param messages conversation history including system prompt
     * @param callback callback for thinking and content chunks
     * @return the full accumulated response text (content only, not thinking)
     * @throws Exception on network/API errors
     */
    default String chatStream(List<ChatMessage> messages, StreamCallback callback) throws Exception {
        // Default fallback: non-streaming
        String result = chat(messages);
        callback.onContent(result);
        return result;
    }

    /**
     * Lightweight connectivity test. Sends minimal payload with max_tokens=5.
     * @return a short reply or error
     * @throws Exception on network/API errors
     */
    default String ping() throws Exception {
        return chat(List.of(new ChatMessage(ROLE_USER, "Hi")));
    }

    /**
     * Get the provider name for display.
     */
    String getProviderName();
}
