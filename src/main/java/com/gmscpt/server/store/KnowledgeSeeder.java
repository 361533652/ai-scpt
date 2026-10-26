package com.gmscpt.server.store;

import com.gmscpt.server.domain.KnowledgeEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 旧版知识库（{@code src/main/resources/knowledge/knowledge.json}）→ {@code kb_entry} 的首次导入。
 *
 * <p>实际 JSON 结构（已核对文件）：顶层是数组，元素是
 * {@code {id, game, title, description, script, tags[]}}，全部字段都是字符串/字符串数组，
 * 没有 {@code source}、{@code enabled}、时间戳——所以这里补默认值：
 * {@code source="legacy:knowledge.json"}、{@code enabled=true}、{@code updated_at=资源文件时间}。
 * 条目 id 沿用 JSON 里的可读 slug（{@code rapid-fire} 之类），不换 ULID：
 * 这样重复导入天然幂等（{@code INSERT OR IGNORE}），也方便和旧版对账。
 *
 * <p><b>绝不覆盖用户数据</b>：只有 {@code kb_entry} 为空时才导入；
 * 而且「空判断」与「插入」在同一个写事务里——两个实例并发启动也只会导入一次。
 *
 * <p><b>绝不阻断启动</b>：文件缺失、JSON 不合法、单条字段缺失都只记日志：
 * 整份解析失败 → 一条不导；单条缺 id/title → 跳过该条。调用方（{@link Database}）还会再兜一层 catch。
 */
public final class KnowledgeSeeder {

    private static final Logger LOG = Logger.getLogger(KnowledgeSeeder.class.getName());

    /** 种子数据资源路径。 */
    public static final String CLASSPATH_RESOURCE = "/knowledge/knowledge.json";

    private static final String SOURCE_TAG = "legacy:knowledge.json";

    private KnowledgeSeeder() {
    }

    /** 若 {@code kb_entry} 为空则导入类路径上的种子；返回实际导入条数。 */
    public static int seedIfEmpty(Database db) {
        return seedIfEmpty(db, null);
    }

    /**
     * 若 {@code kb_entry} 为空则导入；{@code jsonFile} 非空时从该文件读（测试/迁移用），
     * 为空时读类路径资源 {@link #CLASSPATH_RESOURCE}。
     */
    public static int seedIfEmpty(Database db, Path jsonFile) {
        List<KnowledgeEntry> seeds;
        try {
            seeds = jsonFile == null ? loadFromClasspath() : loadFromFile(jsonFile);
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "知识库种子解析失败，跳过导入（不影响启动）", failure);
            return 0;
        }
        if (seeds.isEmpty()) {
            LOG.info("知识库种子为空或不可用，跳过导入");
            return 0;
        }
        try {
            return db.write(connection -> {
                try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM kb_entry");
                     var rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        return 0; // 库里有用户数据：一个字都不动
                    }
                }
                int imported = 0;
                for (KnowledgeEntry entry : seeds) {
                    imported += SqlKnowledgeRepository.insertRow(connection, entry, true);
                }
                return imported;
            });
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "知识库种子写入失败，跳过导入（不影响启动）", failure);
            return 0;
        }
    }

    /** 从类路径读取并解析；资源缺失返回空表。 */
    public static List<KnowledgeEntry> loadFromClasspath() throws IOException {
        try (InputStream in = KnowledgeSeeder.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in == null) {
                LOG.warning("类路径上找不到 " + CLASSPATH_RESOURCE + "，知识库将为空");
                return List.of();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(text, Instant.now());
        }
    }

    /** 从文件读取并解析；条目时间取文件修改时间，便于追溯种子版本。 */
    public static List<KnowledgeEntry> loadFromFile(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Instant updatedAt = Files.getLastModifiedTime(file).toInstant();
        return parse(text, updatedAt);
    }

    /** 解析种子 JSON。整份不合法时抛 {@link IllegalArgumentException}，由调用方选择跳过。 */
    public static List<KnowledgeEntry> parse(String json, Instant updatedAt) {
        Object root = Json.parse(json);
        if (!(root instanceof List<?> rows)) {
            throw new IllegalArgumentException("知识库种子应为 JSON 数组，实际是 " + (root == null ? "null" : root.getClass()));
        }
        Instant stamp = updatedAt == null ? Instant.now() : updatedAt;
        List<KnowledgeEntry> entries = new ArrayList<>(rows.size());
        for (Object row : rows) {
            try {
                var map = Json.object(row);
                String id = Json.string(map.get("id"));
                String title = Json.string(map.get("title"));
                if (id == null || id.isBlank() || title == null || title.isBlank()) {
                    LOG.warning("跳过缺少 id/title 的知识库种子条目: " + row);
                    continue;
                }
                entries.add(new KnowledgeEntry(
                        id.trim(),
                        Json.string(map.get("game")),
                        title,
                        Json.string(map.get("description")),
                        Json.string(map.get("script"), ""),
                        Json.stringList(map.get("tags")),
                        SOURCE_TAG,
                        true,
                        stamp));
            } catch (RuntimeException perEntryFailure) {
                // 单条坏数据不该毁掉整份种子
                LOG.log(Level.WARNING, "跳过无法解析的知识库种子条目", perEntryFailure);
            }
        }
        return entries;
    }
}
