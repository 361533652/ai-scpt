package com.gmscpt.server.store;

import com.gmscpt.server.domain.KnowledgeEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@code kb_entry} 表的 JDBC 实现。
 *
 * <p>存储要点（表结构见 {@link Database} 的 DDL 注释）：
 * <ul>
 *   <li>{@code tags} 是 {@code List<String>}，存 {@code tags_json}（JSON 数组）而不是逗号分隔串——
 *       标签里出现逗号是常态，分隔符方案迟早要转义；</li>
 *   <li>{@code content_hash} = 正文 SHA-256，二期做向量索引时直接拿它判断「这条要不要重算」；</li>
 *   <li>时间存 epoch millis。</li>
 * </ul>
 *
 * <p>检索：一期没有向量，用「关键词子串 + 字段权重」打分。
 * 权重顺序按用户找脚本时的直觉排：标题 > 标签 > 游戏 > 描述 > 正文。
 * 二期引入 {@code kb_chunk} 后，本类的 {@code search} 就是降级路径（对应 API 契约里的 {@code mode:keyword}）。
 */
public final class SqlKnowledgeRepository implements KnowledgeRepository {

    private static final String COLUMNS =
            "id, game, title, description, script, tags_json, source, enabled, content_hash, updated_at";

    private static final int DEFAULT_LIMIT = 200;
    private static final int SEARCH_CANDIDATE_LIMIT = 2_000;

    private final Database db;

    public SqlKnowledgeRepository(Database db) {
        this.db = db;
    }

    @Override
    public List<KnowledgeEntry> findAll(String query, int limit) {
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 10_000);
        return db.read(connection -> {
            String sql = "SELECT " + COLUMNS + " FROM kb_entry";
            boolean filtered = query != null && !query.isBlank();
            if (filtered) {
                sql += " WHERE title LIKE ? ESCAPE '\\' OR game LIKE ? ESCAPE '\\'"
                        + " OR description LIKE ? ESCAPE '\\' OR tags_json LIKE ? ESCAPE '\\'";
            }
            sql += " ORDER BY updated_at DESC, id LIMIT ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int index = 1;
                if (filtered) {
                    String pattern = likePattern(query.trim());
                    ps.setString(index++, pattern);
                    ps.setString(index++, pattern);
                    ps.setString(index++, pattern);
                    ps.setString(index++, pattern);
                }
                ps.setInt(index, capped);
                try (ResultSet rs = ps.executeQuery()) {
                    List<KnowledgeEntry> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public Optional<KnowledgeEntry> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM kb_entry WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<KnowledgeEntry>empty();
                }
            }
        });
    }

    @Override
    public KnowledgeEntry insert(KnowledgeEntry entry) {
        db.write(connection -> {
            insertRow(connection, entry, false);
            return null;
        });
        return findById(entry.id()).orElseThrow(
                () -> new StoreException("知识库条目写入后读不回来: " + entry.id()));
    }

    @Override
    public KnowledgeEntry update(KnowledgeEntry entry) {
        int updated = db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE kb_entry SET game = ?, title = ?, description = ?, script = ?, tags_json = ?,"
                            + " source = ?, enabled = ?, content_hash = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, entry.game());
                ps.setString(2, entry.title());
                ps.setString(3, entry.description());
                ps.setString(4, entry.script());
                ps.setString(5, Json.writeStrings(entry.tags()));
                ps.setString(6, entry.source());
                ps.setInt(7, entry.enabled() ? 1 : 0);
                ps.setString(8, SqlSupport.sha256Hex(entry.script()));
                ps.setLong(9, updatedAt(entry));
                ps.setString(10, entry.id());
                return ps.executeUpdate();
            }
        });
        if (updated == 0) {
            throw new NoSuchElementException("知识库条目不存在: " + entry.id());
        }
        return findById(entry.id()).orElseThrow(
                () -> new StoreException("知识库条目更新后读不回来: " + entry.id()));
    }

    @Override
    public void delete(String id) {
        int deleted = db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM kb_entry WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate();
            }
        });
        if (deleted == 0) {
            throw new NoSuchElementException("知识库条目不存在: " + id);
        }
    }

    @Override
    public List<KnowledgeEntry> search(String query, int topK) {
        int wanted = topK <= 0 ? 10 : topK;
        if (query == null || query.isBlank()) {
            return db.read(connection -> select(connection,
                    "SELECT " + COLUMNS + " FROM kb_entry ORDER BY enabled DESC, updated_at DESC LIMIT ?",
                    List.of(wanted)));
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        // 先用 SQL LIKE 粗筛（能吃到索引/避免全表进内存），再在 Java 里按字段权重打分精排
        List<KnowledgeEntry> candidates = db.read(connection -> {
            String pattern = likePattern(query.trim());
            return select(connection,
                    "SELECT " + COLUMNS + " FROM kb_entry WHERE title LIKE ? ESCAPE '\\' OR game LIKE ? ESCAPE '\\'"
                            + " OR description LIKE ? ESCAPE '\\' OR tags_json LIKE ? ESCAPE '\\'"
                            + " OR script LIKE ? ESCAPE '\\' ORDER BY updated_at DESC LIMIT ?",
                    List.of(pattern, pattern, pattern, pattern, pattern, SEARCH_CANDIDATE_LIMIT));
        });
        return candidates.stream()
                .map(entry -> new Scored(entry, score(entry, needle)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt((Scored s) -> s.score).reversed()
                        .thenComparing(s -> s.entry.enabled() ? 0 : 1)
                        .thenComparing(s -> s.entry.updatedAt() == null ? Instant.EPOCH : s.entry.updatedAt(),
                                Comparator.reverseOrder()))
                .limit(wanted)
                .map(Scored::entry)
                .toList();
    }

    @Override
    public long count() {
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM kb_entry");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // ------------------------------------------------------------ 内部

    /** 供 {@link KnowledgeSeeder} 在同一事务里批量插入；{@code ignoreExisting} 让重复导入变成 no-op。 */
    static int insertRow(Connection connection, KnowledgeEntry entry, boolean ignoreExisting) throws SQLException {
        String verb = ignoreExisting ? "INSERT OR IGNORE INTO kb_entry" : "INSERT INTO kb_entry";
        try (PreparedStatement ps = connection.prepareStatement(verb + " ("
                + COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, entry.id());
            ps.setString(2, entry.game());
            ps.setString(3, entry.title());
            ps.setString(4, entry.description());
            ps.setString(5, entry.script());
            ps.setString(6, Json.writeStrings(entry.tags()));
            ps.setString(7, entry.source());
            ps.setInt(8, entry.enabled() ? 1 : 0);
            ps.setString(9, SqlSupport.sha256Hex(entry.script()));
            ps.setLong(10, updatedAt(entry));
            return ps.executeUpdate();
        }
    }

    private static long updatedAt(KnowledgeEntry entry) {
        return entry.updatedAt() == null ? System.currentTimeMillis() : entry.updatedAt().toEpochMilli();
    }

    private static KnowledgeEntry map(ResultSet rs) throws SQLException {
        return new KnowledgeEntry(
                rs.getString("id"),
                rs.getString("game"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("script"),
                parseTags(rs.getString("tags_json")),
                rs.getString("source"),
                rs.getInt("enabled") != 0,
                SqlSupport.toInstant(rs.getLong("updated_at")));
    }

    /** 脏 {@code tags_json} 只丢标签，不能让整行读不出来。 */
    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.stringList(Json.parse(json));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<KnowledgeEntry> select(Connection connection, String sql, List<Object> params)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<KnowledgeEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        }
    }

    /** 打分：命中字段的权重之和；标题命中额外给「完全相等 / 前缀」加成。 */
    private static int score(KnowledgeEntry entry, String needle) {
        int score = 0;
        String title = lower(entry.title());
        if (title.equals(needle)) {
            score += 12;
        } else if (title.startsWith(needle)) {
            score += 9;
        } else if (title.contains(needle)) {
            score += 6;
        }
        for (String tag : entry.tags()) {
            if (lower(tag).contains(needle)) {
                score += 4;
                break;
            }
        }
        if (lower(entry.game()).contains(needle)) {
            score += 3;
        }
        if (lower(entry.description()).contains(needle)) {
            score += 2;
        }
        if (lower(entry.script()).contains(needle)) {
            score += 1;
        }
        // 出现次数加成：同一个词在标题里重复出现通常意味着「就是这条」
        score += Math.min(3, occurrences(title, needle));
        return score;
    }

    private static int occurrences(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /** LIKE 通配符转义，防止用户输入的 % / _ 变成全表匹配。 */
    private static String likePattern(String raw) {
        String escaped = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private record Scored(KnowledgeEntry entry, int score) {
    }
}
