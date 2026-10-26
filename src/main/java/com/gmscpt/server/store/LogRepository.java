package com.gmscpt.server.store;

import com.gmscpt.server.domain.LogEntry;

import java.util.List;

/**
 * 日志落库端口。实现必须**永不在调用方线程上做慢 IO**：
 * 日志从脚本线程与钩子线程发出，写库要走内部队列异步刷盘。
 */
public interface LogRepository {

    void append(LogEntry entry);

    List<LogEntry> query(String scriptId, String level, long sinceSeq, int limit);

    /** 保留策略：删除早于给定毫秒时间戳的记录。 */
    int deleteOlderThan(long epochMillis);
}
