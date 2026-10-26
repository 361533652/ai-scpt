package com.gmscpt.server.store;

import com.gmscpt.server.domain.KnowledgeEntry;

import java.util.List;
import java.util.Optional;

/** 知识库持久化端口。一期只做关键词检索；二期的向量块表由实现自行扩展。 */
public interface KnowledgeRepository {

    List<KnowledgeEntry> findAll(String query, int limit);

    Optional<KnowledgeEntry> findById(String id);

    KnowledgeEntry insert(KnowledgeEntry entry);

    KnowledgeEntry update(KnowledgeEntry entry);

    void delete(String id);

    /** 关键词检索：标题/描述/标签/正文的子串匹配，按命中权重排序。 */
    List<KnowledgeEntry> search(String query, int topK);

    long count();
}
