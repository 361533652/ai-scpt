package com.gmscpt.server.service;

import com.gmscpt.server.domain.KnowledgeEntry;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.kernel.log.LogBus;
import com.gmscpt.server.store.KnowledgeRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库用例：CRUD + 检索。
 *
 * <p>旧版知识库是从 classpath 读一个静态 JSON，只读、只能做子串匹配，
 * 用户自己攒的模板没有任何地方放。这里至少做到可增删改查，
 * 二期的向量检索（nomic-embed-text）只需要替换 {@link #search} 的实现。
 */
public class KnowledgeService {

    /** 一条检索命中。 */
    public record Hit(KnowledgeEntry entry, double score, String snippet) {
    }

    private final KnowledgeRepository repository;
    private final LogBus logBus;

    public KnowledgeService(KnowledgeRepository repository, LogBus logBus) {
        this.repository = repository;
        this.logBus = logBus;
    }

    public List<KnowledgeEntry> list(String query, int limit) {
        return repository.findAll(query, limit <= 0 ? 200 : limit);
    }

    public KnowledgeEntry get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> ServiceException.notFound("知识条目不存在: " + id));
    }

    public KnowledgeEntry create(KnowledgeEntry entry) {
        KnowledgeEntry toSave = new KnowledgeEntry(
                entry.id() == null || entry.id().isBlank() ? UUID.randomUUID().toString() : entry.id(),
                entry.game(), entry.title(), entry.description(), entry.script(), entry.tags(),
                entry.source() == null ? "user" : entry.source(), entry.enabled(), Instant.now());
        KnowledgeEntry saved = repository.insert(toSave);
        logBus.publish(LogLevel.INFO, "kb", null, null, "新增知识条目：" + saved.title());
        return saved;
    }

    public KnowledgeEntry update(KnowledgeEntry entry) {
        get(entry.id());
        return repository.update(entry);
    }

    public void delete(String id) {
        get(id);
        repository.delete(id);
    }

    public List<Hit> search(String query, int topK) {
        int k = topK <= 0 ? 5 : Math.min(topK, 20);
        List<Hit> hits = new ArrayList<>();
        for (KnowledgeEntry e : repository.search(query, k)) {
            String snippet = e.script() == null ? "" : e.script();
            int idx = query == null ? -1 : snippet.toLowerCase().indexOf(query.toLowerCase());
            if (idx >= 0) {
                int from = Math.max(0, idx - 40);
                int to = Math.min(snippet.length(), idx + 120);
                snippet = "…" + snippet.substring(from, to).replace('\n', ' ') + "…";
            } else if (snippet.length() > 160) {
                snippet = snippet.substring(0, 160).replace('\n', ' ') + "…";
            }
            hits.add(new Hit(e, 1.0, snippet));
        }
        return hits;
    }

    /** 给 AI 用的上下文片段：把勾选的条目拼成文本。 */
    public String buildContext(List<String> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : entryIds) {
            repository.findById(id).ifPresent(e -> {
                sb.append("### ").append(e.title());
                if (e.game() != null && !e.game().isBlank()) sb.append("（").append(e.game()).append("）");
                sb.append('\n');
                if (e.description() != null && !e.description().isBlank()) {
                    sb.append(e.description()).append('\n');
                }
                sb.append("```lua\n").append(e.script()).append("\n```\n\n");
            });
        }
        return sb.toString();
    }
}
