package com.gmscpt.server.service;

import com.gmscpt.server.ai.AiChunk;
import com.gmscpt.server.ai.AiGateway;
import com.gmscpt.server.ai.ChatRequest;
import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatRole;
import com.gmscpt.server.domain.ChatSession;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.kernel.lua.LuaApiDoc;
import com.gmscpt.server.store.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 对话用例。
 *
 * <p>与旧版的差别：会话与消息入库（旧版是一个内存 list，多会话根本不存在）、
 * 同一会话单飞（旧版共享历史数组，两个请求同时发会互相踩）、
 * 支持取消（旧版读超时为 0，上游一挂输入框就永久禁用）、
 * system prompt 由 {@link LuaApiDoc} 生成（旧版是手抄副本，早就漂移了）。
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:lua)?\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int CONTEXT_CHAR_BUDGET = 12000;

    /** 一帧 SSE。 */
    public record Frame(String event, Object data) {
    }

    /** 对话入参。 */
    public record ChatInput(String sessionId, String message, String providerId, String model,
                            String scriptId, Boolean includeScript, List<String> kbEntryIds) {
    }

    /**
     * 一次已建立会话的流式请求。
     *
     * <p>为什么不直接在 {@code stream()} 里登记订阅：Reactor 的 {@code doOnSubscribe} 拿到的是
     * {@code Subscription} 而不是 {@code Disposable}，取消要靠订阅方持有的 {@code Disposable}。
     * 由控制器订阅后调用 {@link #track} 登记，取消路径才真的握得住句柄。
     */
    public record StreamHandle(String sessionId, Flux<Frame> frames) {
    }

    private final ChatRepository chatRepository;
    private final KnowledgeService knowledgeService;
    private final SettingsService settingsService;
    private final LuaApiDoc apiDoc;
    private final LogBus logBus;
    private final Map<String, AiGateway> gateways = new ConcurrentHashMap<>();
    private final Map<String, Disposable> inFlight = new ConcurrentHashMap<>();

    public ChatService(ChatRepository chatRepository,
                       KnowledgeService knowledgeService,
                       SettingsService settingsService,
                       LuaApiDoc apiDoc,
                       LogBus logBus,
                       List<AiGateway> gatewayList) {
        this.chatRepository = chatRepository;
        this.knowledgeService = knowledgeService;
        this.settingsService = settingsService;
        this.apiDoc = apiDoc;
        this.logBus = logBus;
        for (AiGateway gw : gatewayList) {
            gateways.put(gw.id(), gw);
        }
    }

    public List<ChatSession> sessions() {
        return chatRepository.sessions();
    }

    public List<ChatMessage> messages(String sessionId) {
        return chatRepository.messages(sessionId, 200);
    }

    public void deleteSession(String sessionId) {
        chatRepository.deleteSession(sessionId);
    }

    /** 取消某会话在飞的请求。 */
    public boolean cancel(String sessionId) {
        Disposable d = inFlight.remove(sessionId);
        if (d != null && !d.isDisposed()) {
            d.dispose();
            return true;
        }
        return false;
    }

    public boolean cancelAll() {
        boolean any = false;
        for (String key : new ArrayList<>(inFlight.keySet())) {
            any |= cancel(key);
        }
        return any;
    }

    /**
     * 发起一次流式对话。
     *
     * <p>返回的 Flux 是「懒」的：控制器订阅它才会真的开始请求上游；
     * 取消（或调用 {@link #cancel}）会 dispose 上游。
     */
    public StreamHandle openStream(ChatInput input) {
        if (input.message() == null || input.message().isBlank()) {
            throw ServiceException.badRequest("消息不能为空");
        }
        String providerId = input.providerId() == null || input.providerId().isBlank()
                ? settingsService.get().providerId() : input.providerId();
        AiGateway gateway = gateways.get(providerId);
        if (gateway == null) {
            throw ServiceException.badRequest("未知的 AI 通道: " + providerId);
        }

        ChatSession session = resolveSession(input, providerId);
        chatRepository.appendMessage(new ChatMessage(UUID.randomUUID().toString(), session.id(),
                ChatRole.USER, input.message(), null, 0, 0, providerId, input.model(), Instant.now()));

        List<ChatMessage> history = chatRepository.messages(session.id(), MAX_HISTORY_MESSAGES);
        String systemPrompt = buildSystemPrompt(input);

        ChatRequest request = new ChatRequest(session.id(), providerId, input.model(),
                systemPrompt, trimmedHistory(history), input.message(), 0.3);

        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String messageId = UUID.randomUUID().toString();

        Map<String, Object> started = new LinkedHashMap<>();
        started.put("sessionId", session.id());
        started.put("messageId", messageId);
        started.put("providerId", providerId);
        started.put("model", input.model() == null ? gateway.info().models().stream().findFirst().orElse(null) : input.model());

        Flux<Frame> body = Flux.concat(
                        Flux.just(new Frame("started", started)),
                        gateway.stream(request).map(chunk -> toFrame(chunk, content, thinking)),
                        Flux.defer(() -> {
                            List<String> blocks = extractLuaBlocks(content.toString());
                            chatRepository.appendMessage(new ChatMessage(messageId, session.id(),
                                    ChatRole.ASSISTANT, content.toString(),
                                    thinking.isEmpty() ? null : thinking.toString(),
                                    0, 0, providerId, input.model(), Instant.now()));
                            Map<String, Object> done = new LinkedHashMap<>();
                            done.put("messageId", messageId);
                            done.put("codeBlocks", blocks);
                            logBus.publish(LogLevel.INFO, "ai", null, null,
                                    "AI 回复完成（" + providerId + "）：" + content.length() + " 字符"
                                            + (blocks.isEmpty() ? "" : "，含 " + blocks.size() + " 个代码块"));
                            return Flux.just(new Frame("done", done));
                        }))
                .onErrorResume(error -> {
                    logBus.publish(LogLevel.ERROR, "ai", null, null, "AI 请求失败：" + error.getMessage());
                    Map<String, Object> err = new LinkedHashMap<>();
                    String code = error instanceof java.util.concurrent.TimeoutException ? "LLM_TIMEOUT" : "LLM_UPSTREAM_ERROR";
                    err.put("code", code);
                    err.put("message", error.getMessage() == null ? String.valueOf(error) : error.getMessage());
                    return Flux.just(new Frame("error", err));
                })
                .doFinally(signal -> inFlight.remove(session.id()))
                .doOnCancel(() -> logBus.publish(LogLevel.INFO, "ai", null, null, "用户取消了 AI 请求"));

        return new StreamHandle(session.id(), body);
    }

    /** 由控制器在订阅后登记，取消时才有句柄可 dispose。 */
    public void track(String sessionId, Disposable disposable) {
        inFlight.put(sessionId, disposable);
    }

    private ChatSession resolveSession(ChatInput input, String providerId) {
        if (input.sessionId() != null && !input.sessionId().isBlank()) {
            return chatRepository.session(input.sessionId())
                    .orElseGet(() -> chatRepository.createSession(new ChatSession(UUID.randomUUID().toString(),
                            titleOf(input.message()), providerId, input.model(), Instant.now())));
        }
        return chatRepository.createSession(new ChatSession(UUID.randomUUID().toString(),
                titleOf(input.message()), providerId, input.model(), Instant.now()));
    }

    private static String titleOf(String message) {
        String t = message.strip().replace('\n', ' ');
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }

    private Frame toFrame(AiChunk chunk, StringBuilder content, StringBuilder thinking) {
        if (chunk.done()) {
            // done 帧由后面的 defer 统一发（那时才持完整正文）
            return new Frame("noop", Map.of());
        }
        if (chunk.channel() == null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("tokensIn", chunk.tokensIn());
            usage.put("tokensOut", chunk.tokensOut());
            return new Frame("usage", usage);
        }
        if ("thinking".equals(chunk.channel())) {
            thinking.append(chunk.text() == null ? "" : chunk.text());
        } else {
            content.append(chunk.text() == null ? "" : chunk.text());
        }
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("channel", chunk.channel());
        delta.put("text", chunk.text());
        return new Frame("delta", delta);
    }

    private List<String> extractLuaBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher m = CODE_BLOCK.matcher(text == null ? "" : text);
        while (m.find()) {
            String code = m.group(1).strip();
            if (!code.isEmpty()) blocks.add(code);
        }
        return blocks;
    }

    private String buildSystemPrompt(ChatInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 GmScript 的 Lua 宏脚本助手：把用户用中文描述的操作意图，写成可直接运行的 GmScript Lua 脚本。\n\n");
        sb.append(apiDoc.systemPromptFragment());
        sb.append("\n输出要求：\n");
        sb.append("1. 需要脚本时，只输出一个 ```lua 代码块，块内是完整可运行脚本，不要输出半截片段。\n");
        sb.append("2. 代码里不要写解释性长注释，关键步骤一两句中文短注释即可。\n");
        sb.append("3. 用户只是问问题时，直接用中文简短回答，不要强行给代码。\n");
        sb.append("4. 绝对不要使用 API 列表之外的函数；不确定就说明做不到。\n");

        String kb = knowledgeService.buildContext(input.kbEntryIds());
        if (!kb.isBlank()) {
            sb.append("\n可参考的知识库片段（用户已勾选）：\n").append(kb);
        }
        return sb.toString();
    }

    /** 按字符预算裁剪历史，超出的部分明确标注省略了多少轮，而不是静默丢弃。 */
    private List<ChatMessage> trimmedHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<ChatMessage> usable = new ArrayList<>(history);
        // 最后一条是本轮用户消息，已通过 ChatRequest.userMessage 单独传递
        if (!usable.isEmpty() && usable.get(usable.size() - 1).role() == ChatRole.USER) {
            usable.remove(usable.size() - 1);
        }
        int budget = CONTEXT_CHAR_BUDGET;
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = usable.size() - 1; i >= 0; i--) {
            ChatMessage m = usable.get(i);
            int cost = m.content() == null ? 0 : m.content().length();
            if (budget - cost < 0) break;
            budget -= cost;
            kept.add(0, m);
        }
        int omitted = usable.size() - kept.size();
        if (omitted > 0) {
            kept.add(0, new ChatMessage("omitted", "", ChatRole.SYSTEM,
                    "（较早的 " + omitted + " 轮对话已因上下文预算被省略）", null, 0, 0, null, null, Instant.now()));
        }
        return kept;
    }
}
