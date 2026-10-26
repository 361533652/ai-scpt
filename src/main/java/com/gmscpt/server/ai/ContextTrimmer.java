package com.gmscpt.server.ai;

import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatRole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 按字符预算裁剪对话历史。
 *
 * <p><b>为什么用字符估算而不是真 tokenizer</b>：本地 Ollama 的 tokenizer 随模型走，
 * 引一个 tokenizer 库等于新增依赖（红线禁止）；而中英混排下 chars/2.5 是偏保守的经验值
 * ——中文约 1 字 1 token、英文约 4 字符 1 token，混起来落在 2~3 之间。
 * 取 2.5 并向下取整，宁可早一点开始折叠，也不要超出上下文窗口后被上游截断（那会静默丢开头）。
 *
 * <p><b>为什么不能静默丢弃</b>：旧版直接把超出的历史扔掉，模型于是「忘了」用户三轮前说过
 * 「不要用 while 循环」，然后反复给出被否掉的方案。这里把被丢掉的轮次折叠成一条明确的
 * 系统注记（「较早对话已省略 N 轮」），模型能据此知道自己缺上下文，可以主动追问。
 *
 * <p>保留策略：system + <b>最近 K 轮</b>（一轮 = 一问一答）。K 是第二道闸——即使预算很宽，
 * 也不把 200 条历史全塞进去，避免无关旧话题把当前意图带偏。
 */
public class ContextTrimmer {

    /** 估算用：多少字符 ≈ 1 token。保守取值见类注释。 */
    static final double CHARS_PER_TOKEN = 2.5;

    /** 默认预算（token 数，不是字符数）。qwen2.5-coder 的 num_ctx 默认 32k，留一半给生成与系统提示词。 */
    public static final int DEFAULT_TOKEN_BUDGET = 6000;

    /** 默认保留最近多少轮（一问一答算一轮）。 */
    public static final int DEFAULT_KEEP_ROUNDS = 6;

    private final int tokenBudget;
    private final int keepRounds;

    public ContextTrimmer() {
        this(DEFAULT_TOKEN_BUDGET, DEFAULT_KEEP_ROUNDS);
    }

    public ContextTrimmer(int tokenBudget, int keepRounds) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget 必须为正数");
        }
        if (keepRounds <= 0) {
            throw new IllegalArgumentException("keepRounds 必须为正数");
        }
        this.tokenBudget = tokenBudget;
        this.keepRounds = keepRounds;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public int keepRounds() {
        return keepRounds;
    }

    /** 字符预算 = token 预算 × 每 token 字符数。 */
    public int charBudget() {
        return (int) Math.floor(tokenBudget * CHARS_PER_TOKEN);
    }

    /** 保守估算一段文本占多少 token。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 裁剪历史。
     *
     * <p>输入不做删改（调用方可能还要用它落库），返回一个新列表：
     * 保证「有省略时首条是一条 SYSTEM 注记」，之后是保留下来的最近若干轮。
     *
     * @param history    原始历史，可含 SYSTEM 消息（会被保留在注记之外的位置）
     * @param systemText 系统提示词的文本，用于预留它占的预算（它不进返回列表，但吃窗口）
     */
    public List<ChatMessage> trim(List<ChatMessage> history, String systemText) {
        return trim(history, systemText, this.tokenBudget, this.keepRounds);
    }

    /**
     * @param budgetTokens 本次可用的 token 预算（已扣掉 system 与预留生成空间后的净值）
     */
    public List<ChatMessage> trim(List<ChatMessage> history, String systemText,
                                  int budgetTokens, int maxRounds) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        // 预算换算成字符；系统提示词本身占的额度先扣掉。
        int budgetChars = (int) Math.floor(budgetTokens * CHARS_PER_TOKEN);
        int systemChars = systemText == null ? 0 : systemText.length();
        int remaining = budgetChars - systemChars;

        // 先把历史拆成两个池：真正的对话轮次，与调用方已有的系统注记。
        List<ChatMessage> dialogue = new ArrayList<>();
        List<String> systemNotes = new ArrayList<>();
        for (ChatMessage m : history) {
            if (m == null) {
                continue;
            }
            if (m.role() == ChatRole.SYSTEM) {
                if (m.content() != null && !m.content().isBlank()) {
                    systemNotes.add(m.content().strip());
                }
            } else if (m.content() != null && !m.content().isBlank()) {
                dialogue.add(m);
            }
        }
        if (dialogue.isEmpty()) {
            return systemNotes.isEmpty() ? List.of() : List.of(note(sessionIdOf(history), String.join("\n", systemNotes)));
        }

        // 从最新往回收集，同时受两道闸限制：字符预算 + 最近 K 轮。
        // 一轮 = 一个 USER 及其后的 ASSISTANT；按「反向遇到的 USER 条数」计轮。
        int maxMessagesByRounds = maxRounds * 2 + 1;
        List<ChatMessage> kept = new ArrayList<>();
        int rounds = 0;
        for (int i = dialogue.size() - 1; i >= 0; i--) {
            ChatMessage m = dialogue.get(i);
            int cost = m.content().length();

            // 第一条无论如何都保留（否则可能出现「什么历史都没有」却报告省略了 N 轮）。
            boolean firstKept = kept.isEmpty();
            boolean overBudget = !firstKept && remaining - cost < 0;
            boolean overRounds = !firstKept && kept.size() >= maxMessagesByRounds;
            if (overBudget || overRounds) {
                break;
            }
            remaining -= cost;
            kept.add(0, m);
            if (m.role() == ChatRole.USER) {
                rounds++;
            }
        }

        int omittedMessages = dialogue.size() - kept.size();
        int omittedRounds = Math.max(0, countUserMessages(dialogue) - rounds);

        List<ChatMessage> out = new ArrayList<>(kept.size() + 1);
        StringBuilder notes = new StringBuilder(String.join("\n", systemNotes));
        if (omittedMessages > 0) {
            if (!notes.isEmpty()) {
                notes.append('\n');
            }
            // 明确写「省略了 N 轮」，且告诉模型这意味着什么——只写「已省略」模型往往假装没缺上下文。
            notes.append("（较早对话已省略 ").append(Math.max(omittedRounds, 1)).append(" 轮，")
                    .append("共 ").append(omittedMessages).append(" 条消息：超出上下文预算。")
                    .append("若这些内容与当前任务相关，请让用户补充关键信息，不要凭空猜测之前说过什么。）");
        }
        if (!notes.isEmpty()) {
            out.add(note(sessionIdOf(history), notes.toString()));
        }
        out.addAll(kept);
        return out;
    }

    /**
     * 构造一条用于承载省略说明的 SYSTEM 消息。
     *
     * <p>注意 {@link ChatMessage} 的紧凑构造器<b>拒绝空 sessionId</b>（真机跑出来的教训：
     * 这里最初传了空串，直接 {@code IllegalArgumentException}）。所以注记必须沿用历史里的
     * 真实会话 id —— 反正这条消息最终也只是被 {@code AiMessages} 折进系统提示词，不会落库。
     */
    private static ChatMessage note(String sessionId, String text) {
        return new ChatMessage("context-trim-note", sessionId, ChatRole.SYSTEM,
                text, null, 0, 0, null, null, Instant.now());
    }

    /** 从历史里取会话 id；取不到时给一个明确的占位串，绝不传空。 */
    private static String sessionIdOf(List<ChatMessage> history) {
        if (history != null) {
            for (ChatMessage m : history) {
                if (m != null && m.sessionId() != null && !m.sessionId().isBlank()) {
                    return m.sessionId();
                }
            }
        }
        return "context-trim";
    }

    private static int countUserMessages(List<ChatMessage> dialogue) {
        int n = 0;
        for (ChatMessage m : dialogue) {
            if (m.role() == ChatRole.USER) {
                n++;
            }
        }
        return n;
    }
}
