package com.gmscpt.server.store;

import com.gmscpt.server.domain.RunRecord;

import java.util.List;

/** 运行历史的持久化端口。 */
public interface RunRepository {

    void insert(RunRecord record);

    void finish(RunRecord record);

    List<RunRecord> findByScript(String scriptId, int limit);

    List<RunRecord> recent(int limit);
}
