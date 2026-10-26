package com.gmscpt.server.store;

import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatSession;

import java.util.List;
import java.util.Optional;

/** 对话会话与消息的持久化端口。 */
public interface ChatRepository {

    List<ChatSession> sessions();

    Optional<ChatSession> session(String id);

    ChatSession createSession(ChatSession session);

    void renameSession(String id, String title);

    void deleteSession(String id);

    List<ChatMessage> messages(String sessionId, int limit);

    ChatMessage appendMessage(ChatMessage message);
}
