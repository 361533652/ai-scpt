package com.gmscpt.server.domain;

import java.time.Instant;

/**
 * 一个宏脚本的完整记录。{@code source} 是 Lua 正文，以 {@code ~/.gmscpt/scripts/<id>.lua} 为真源，
 * 数据库里存的是索引与元数据（见 ADR-0001）。
 *
 * @param rev 每次内容变更自增，用于乐观锁（客户端提交旧 rev 会被拒）
 */
public record Script(
        String id,
        String name,
        String description,
        String source,
        long rev,
        ScriptStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public Script {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("script id 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("script name 不能为空");
        if (source == null) source = "";
        if (status == null) status = ScriptStatus.ENABLED;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    public Script withSource(String newSource, long newRev) {
        return new Script(id, name, description, newSource, newRev, status, createdAt, Instant.now());
    }
}
