package com.gmscpt.agent;

import com.gmscpt.agent.impl.OpenAiClient;
import com.gmscpt.config.AppConfig;
import com.gmscpt.lua.LuaApiRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Agent that manages conversation history and the built-in system prompt.
 * The system prompt is injected at startup and not exposed to the user.
 */
public class AiAgent {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a game macro script generation assistant for GmScript.
            Your job is to help users create Lua scripts for automating mouse/keyboard operations in games.
            
            ## Rules:
            1. When the user asks a general question (e.g. "what can you do", "help"), answer briefly in natural language. Do NOT force code output.
            2. When the user requests a script or macro, output ONLY runnable Lua code blocks with brief comments. No lengthy explanations.
            3. All scripts MUST define an OnEvent(event, arg) function as the entry point.
            4. Use ONLY the API functions listed below. Do NOT invent or use any other functions.
            5. Key names for keyboard API: "a"-"z", "0"-"9", "f1"-"f12", "shift", "ctrl", "alt", "space", "enter", "tab", "esc", "backspace", "delete", "up", "down", "left", "right"
            6. Mouse buttons: 1=left, 2=middle, 3=right
            7. Always add Sleep() between repeated actions to avoid overwhelming the system.
            8. Respond in the same language the user uses (Chinese if user speaks Chinese).
            9. If [Knowledge Base] context is provided, use it as reference for game mechanics and script patterns.
            
            ## Available API:
            %s
            """;

    private final List<LlmClient.ChatMessage> history = new ArrayList<>();
    private LlmClient client;

    public AiAgent() {
        rebuildClient();
        // Inject system prompt
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, LuaApiRegistry.getApiDocumentation());
        history.add(new LlmClient.ChatMessage(LlmClient.ROLE_SYSTEM, systemPrompt));
    }

    /**
     * Rebuild the LLM client based on current config.
     */
    public void rebuildClient() {
        AppConfig config = AppConfig.getInstance();
        client = new OpenAiClient(config.getApiKey(), config.getModelName(), config.getApiBaseUrl());
    }

    /**
     * Send a user message and get AI response.
     * @param userMessage the user's input
     * @param knowledgeContext optional knowledge base content (nullable)
     * @return AI response text
     */
    public String sendMessage(String userMessage, String knowledgeContext) throws Exception {
        String fullMessage = userMessage;
        if (knowledgeContext != null && !knowledgeContext.isBlank()) {
            fullMessage = "[Knowledge Base]\n" + knowledgeContext + "\n\n[User Request]\n" + userMessage;
        }

        history.add(new LlmClient.ChatMessage(LlmClient.ROLE_USER, fullMessage));

        String response = client.chat(history);

        history.add(new LlmClient.ChatMessage(LlmClient.ROLE_ASSISTANT, response));

        // Keep history manageable (system + last 20 messages)
        while (history.size() > 21) {
            history.remove(1); // Remove oldest non-system message
        }

        return response;
    }

    /**
     * Send a user message without knowledge context.
     */
    public String sendMessage(String userMessage) throws Exception {
        return sendMessage(userMessage, null);
    }

    /**
     * Send a user message with streaming response.
     * @param userMessage the user's input
     * @param knowledgeContext optional knowledge base content (nullable)
     * @param callback callback for thinking and content chunks
     * @return full accumulated response (content only)
     */
    public String sendMessageStream(String userMessage, String knowledgeContext, LlmClient.StreamCallback callback) throws Exception {
        String fullMessage = userMessage;
        if (knowledgeContext != null && !knowledgeContext.isBlank()) {
            fullMessage = "[Knowledge Base]\n" + knowledgeContext + "\n\n[User Request]\n" + userMessage;
        }

        history.add(new LlmClient.ChatMessage(LlmClient.ROLE_USER, fullMessage));

        String response = client.chatStream(history, callback);

        history.add(new LlmClient.ChatMessage(LlmClient.ROLE_ASSISTANT, response));

        while (history.size() > 21) {
            history.remove(1);
        }

        return response;
    }

    /**
     * Clear conversation history (keeps system prompt).
     */
    public void clearHistory() {
        LlmClient.ChatMessage system = history.get(0);
        history.clear();
        history.add(system);
    }

    /**
     * Lightweight connectivity test (no system prompt, max_tokens=5).
     */
    public String ping() throws Exception {
        return client.ping();
    }

    public String getProviderName() {
        return client != null ? client.getProviderName() : "Unknown";
    }
}
