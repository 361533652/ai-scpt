package com.gmscpt.server.api;

import com.gmscpt.server.domain.KnowledgeEntry;
import com.gmscpt.server.service.KnowledgeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 知识库接口。语义见 {@code docs/api-contract.md} 第 7 节。 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    public record SearchRequest(String query, Integer topK, String mode) {
    }

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "200") int limit) {
        return Map.of("items", knowledgeService.list(q, limit));
    }

    @PostMapping
    public ResponseEntity<KnowledgeEntry> create(@RequestBody KnowledgeEntry entry) {
        // 新建时 enabled 缺省为 true：boolean 在 JSON 里省略就是 false，
        // 而「用户刚建的条目默认不可用」会让它在 AI 上下文里凭空消失。
        KnowledgeEntry toCreate = entry.enabled() ? entry : new KnowledgeEntry(
                entry.id(), entry.game(), entry.title(), entry.description(), entry.script(),
                entry.tags(), entry.source(), true, entry.updatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeService.create(toCreate));
    }

    @PutMapping("/{id}")
    public KnowledgeEntry update(@PathVariable String id, @RequestBody KnowledgeEntry entry) {
        KnowledgeEntry current = knowledgeService.get(id);
        return knowledgeService.update(new KnowledgeEntry(id, entry.game(), entry.title(),
                entry.description(), entry.script(), entry.tags(),
                entry.source() == null ? current.source() : entry.source(),
                entry.enabled(), java.time.Instant.now()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest req) {
        if (req.query() == null || req.query().isBlank()) {
            return Map.of("hits", List.of());
        }
        return Map.of("hits", knowledgeService.search(req.query(), req.topK() == null ? 5 : req.topK()));
    }
}
