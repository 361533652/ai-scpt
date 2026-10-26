package com.gmscpt.server.ai;

import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link ChatMessage} 历史装配成模型能吃的消息序列。
 *
 * <p>为什么要单独收一个工具类：历史里可能混进 {@link ContextTrimmer} 生成的 SYSTEM 提示
 * （「较早对话已省略 N 轮」）。有些 OpenAI 兼容端点（尤其第三方代理）只在<b>首条</b>接受
 * system 角色，中间再插一条 system 会直接 400。所以统一做法是：把这类系统提示
 * <b>折叠进首条 system 提示词</b>，对话主体只留 USER/ASSISTANT，两条通道行为一致。
 */
final class AiMessages {

    private AiMessages() {
    }

    /** 历史里的系统级注记（如省略提示），按出现顺序拼起来。 */
    static String foldSystemNotes(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            if (m != null && m.role() == ChatRole.SYSTEM && m.content() != null && !m.content().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(m.content().strip());
            }
        }
        return sb.toString();
    }

    /** 只保留真正的对话轮次（USER/ASSISTANT），SYSTEM 已由 {@link #foldSystemNotes} 折叠。 */
    static List<ChatMessage> dialogue(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>(history.size());
        for (ChatMessage m : history) {
            if (m != null && m.role() != ChatRole.SYSTEM && m.content() != null && !m.content().isBlank()) {
                out.add(m);
            }
        }
        return out;
    }

    /** 系统提示词 + 折叠进来的注记，中间空一行。 */
    static String mergeSystemPrompt(String systemPrompt, List<ChatMessage> history) {
        String base = systemPrompt == null ? "" : systemPrompt.strip();
        String notes = foldSystemNotes(history);
        if (notes.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? notes : base + "\n\n" + notes;
    }
}
