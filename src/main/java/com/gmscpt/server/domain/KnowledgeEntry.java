package com.gmscpt.server.domain;

import java.time.Instant;
import java.util.List;

/**
 * 知识库条目：一条可复用的脚本模板 / 经验。
 * 二期会把 {@code script} 分块向量化进 {@code kb_chunk}，用 nomic-embed-text 做语义检索。
 */
public record KnowledgeEntry(
        String id,
        String game,
        String title,
        String description,
        String script,
        List<String> tags,
        String source,
        boolean enabled,
        Instant updatedAt
) {    public KnowledgeEntry {
        // 允许 id 为空：新建条目时 id 由服务层生成（UUID）。
        // 这里曾经要求 id 非空，导致 POST /api/kb（请求体按契约就是不带 id 的）
        // 在反序列化阶段直接抛异常，接口稳定 500——由前端联调发现。
        if (id != null && id.isBlank()) {
            id = null;
        }
        if (tags == null) tags = List.of();
        else tags = List.copyOf(tags);
        if (script == null) script = "";
    }
}
