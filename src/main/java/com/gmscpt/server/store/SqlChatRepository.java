package com.gmscpt.server.store;

import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatRole;
import com.gmscpt.server.domain.ChatSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@code chat_session} / {@code chat_message} 两张表的 JDBC 实现。
 *
 * <p>存储要点：
 * <ul>
 *   <li>{@code chat_message} 用自增 {@code seq} 当主键、{@code id} 做唯一列。
 *       为什么：消息顺序必须**按插入顺序**稳定，而 {@code created_at} 是毫秒时间戳，
 *       流式对话里同一毫秒连发两条完全可能（还会因为时钟精度相同而无法排序）。
 *       用 seq 排序，分页与「取最近 N 条」都不会抖动；</li>
 *   <li>{@code session_id} 上有外键 + {@code ON DELETE CASCADE}（连接层已开 {@code foreign_keys=ON}），
 *       删会话自动带走消息，不需要应用层再写一遍删除逻辑；</li>
 *   <li>{@code thinking}（qwen3 的思考链）与正文分列存放，界面上折叠展示时不至于把它混进正文；</li>
 *   <li>列名用 {@code provider_id}（与 {@code ChatMessage.providerId} 同名），
 *       架构文档里写的是 {@code provider}，语义一致。</li>
 * </ul>
 */
public final class SqlChatRepository implements ChatRepository {

    private static final String SESSION_COLUMNS = "id, title, provider_id, model, created_at";
    private static final String MESSAGE_COLUMNS =
            "id, session_id, role, content, thinking, tokens_in, tokens_out, provider_id, model, created_at";

    private final Database db;

    public SqlChatRepository(Database db) {
        this.db = db;
    }

    @Override
    public List<ChatSession> sessions() {
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + SESSION_COLUMNS + " FROM chat_session ORDER BY created_at DESC, id DESC");
                 ResultSet rs = ps.executeQuery()) {
                List<ChatSession> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapSession(rs));
                }
                return out;
            }
        });
    }

    @Override
    public Optional<ChatSession> session(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + SESSION_COLUMNS + " FROM chat_session WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapSession(rs)) : Optional.<ChatSession>empty();
                }
            }
        });
    }

    @Override
    public ChatSession createSession(ChatSession session) {
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chat_session(" + SESSION_COLUMNS + ") VALUES(?, ?, ?, ?, ?)")) {
                ps.setString(1, session.id());
                ps.setString(2, session.title());
                ps.setString(3, session.providerId());
                ps.setString(4, session.model());
                ps.setLong(5, session.createdAt().toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
        return session(session.id()).orElseThrow(
                () -> new StoreException("会话写入后读不回来: " + session.id()));
    }

    @Override
    public void renameSession(String id, String title) {
        int updated = db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE chat_session SET title = ? WHERE id = ?")) {
                ps.setString(1, title == null || title.isBlank() ? "新对话" : title);
                ps.setString(2, id);
                return ps.executeUpdate();
            }
        });
        if (updated == 0) {
            throw new NoSuchElementException("会话不存在: " + id);
        }
    }

    @Override
    public void deleteSession(String id) {
        int deleted = db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM chat_session WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate();
            }
        });
        if (deleted == 0) {
            throw new NoSuchElementException("会话不存在: " + id);
        }
    }

    /** 取最近 {@code limit} 条消息，返回时按时间正序（喂给模型的顺序）。 */
    @Override
    public List<ChatMessage> messages(String sessionId, int limit) {
        int capped = limit <= 0 ? 200 : Math.min(limit, 5_000);
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    // 子查询带 seq 才能在外层按 seq 正序：取最近 N 条，再翻回时间正序
                    "SELECT " + MESSAGE_COLUMNS + " FROM (SELECT * FROM chat_message"
                            + " WHERE session_id = ? ORDER BY seq DESC LIMIT ?)"
                            + " ORDER BY seq ASC")) {
                ps.setString(1, sessionId);
                ps.setInt(2, capped);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ChatMessage> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapMessage(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public ChatMessage appendMessage(ChatMessage message) {
        String id = (message.id() == null || message.id().isBlank()) ? IdGenerator.next() : message.id();
        ChatMessage stored = new ChatMessage(
                id,
                message.sessionId(),
                message.role(),
                message.content(),
                message.thinking(),
                message.tokensIn(),
                message.tokensOut(),
                message.providerId(),
                message.model(),
                message.createdAt());
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chat_message(" + MESSAGE_COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, stored.id());
                ps.setString(2, stored.sessionId());
                ps.setString(3, SqlSupport.nameOf(stored.role()));
                ps.setString(4, stored.content());
                ps.setString(5, stored.thinking());
                ps.setInt(6, stored.tokensIn());
                ps.setInt(7, stored.tokensOut());
                ps.setString(8, stored.providerId());
                ps.setString(9, stored.model());
                ps.setLong(10, stored.createdAt().toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
        return stored;
    }

    private static ChatSession mapSession(ResultSet rs) throws SQLException {
        return new ChatSession(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("provider_id"),
                rs.getString("model"),
                SqlSupport.toInstant(rs.getLong("created_at")));
    }

    private static ChatMessage mapMessage(ResultSet rs) throws SQLException {
        return new ChatMessage(
                rs.getString("id"),
                rs.getString("session_id"),
                SqlSupport.parseEnum(ChatRole.class, rs.getString("role"), ChatRole.USER),
                rs.getString("content"),
                rs.getString("thinking"),
                rs.getInt("tokens_in"),
                rs.getInt("tokens_out"),
                rs.getString("provider_id"),
                rs.getString("model"),
                SqlSupport.toInstant(rs.getLong("created_at")));
    }
}
