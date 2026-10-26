package com.gmscpt.server.store;

import com.gmscpt.server.domain.RunExit;
import com.gmscpt.server.domain.RunRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code run} 表的 JDBC 实现（运行历史）。
 *
 * <p>入库时机是「开始一次运行」先 {@link #insert}（只有 started_at，ended_at/exit 为空），
 * 结束时 {@link #finish} 补齐结局。这样进程被强杀也能留下「有过这么一次运行」的痕迹，
 * 而不是一条记录都没有——旧版运行历史只活在内存里，崩了什么都查不到。
 *
 * <p>{@code dropped_events} 是 domain 的 {@code RunRecord} 有、架构文档表结构漏掉的列，
 * 这里补上：事件队列溢出计数是排查「脚本行为异常但没报错」的关键线索。
 *
 * <p>{@link #finish} 对「没有先 insert 就 finish」是容错的（退化为整行写入），
 * 因为旧代码路径或外部调用者可能直接补记一条历史，不该因此丢数据。
 */
public final class SqlRunRepository implements RunRepository {

    private static final String COLUMNS =
            "id, script_id, trigger, started_at, ended_at, exit, error_message,"
                    + " event_count, action_count, dropped_events";

    private final Database db;

    public SqlRunRepository(Database db) {
        this.db = db;
    }

    @Override
    public void insert(RunRecord record) {
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO run(" + COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(ps, record);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void finish(RunRecord record) {
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE run SET ended_at = ?, exit = ?, error_message = ?, event_count = ?,"
                            + " action_count = ?, dropped_events = ? WHERE id = ?")) {
                ps.setObject(1, SqlSupport.toMillis(record.endedAt()));
                ps.setString(2, SqlSupport.nameOf(record.exit()));
                ps.setString(3, record.errorMessage());
                ps.setInt(4, record.eventCount());
                ps.setInt(5, record.actionCount());
                ps.setInt(6, record.droppedEvents());
                ps.setString(7, record.id());
                if (ps.executeUpdate() == 0) {
                    // 没有 insert 就直接 finish：整行补写，历史不能丢
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT OR REPLACE INTO run(" + COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bind(insert, record);
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    @Override
    public List<RunRecord> findByScript(String scriptId, int limit) {
        return db.read(connection -> select(connection,
                "SELECT " + COLUMNS + " FROM run WHERE script_id = ? ORDER BY started_at DESC, id DESC LIMIT ?",
                scriptId, cap(limit)));
    }

    @Override
    public List<RunRecord> recent(int limit) {
        return db.read(connection -> select(connection,
                "SELECT " + COLUMNS + " FROM run ORDER BY started_at DESC, id DESC LIMIT ?",
                null, cap(limit)));
    }

    /** 最近一次运行（含未结束的），供 UI「当前在跑什么」查询。 */
    public Optional<RunRecord> latestForScript(String scriptId) {
        List<RunRecord> rows = db.read(connection -> select(connection,
                "SELECT " + COLUMNS + " FROM run WHERE script_id = ? ORDER BY started_at DESC, id DESC LIMIT 1",
                scriptId, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static int cap(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 10_000);
    }

    private static List<RunRecord> select(Connection connection, String sql, String scriptId, int limit)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            if (scriptId != null) {
                ps.setString(index++, scriptId);
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<RunRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        }
    }

    private static void bind(PreparedStatement ps, RunRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.scriptId());
        ps.setString(3, record.trigger());
        ps.setLong(4, record.startedAt().toEpochMilli());
        ps.setObject(5, SqlSupport.toMillis(record.endedAt()));
        ps.setString(6, SqlSupport.nameOf(record.exit()));
        ps.setString(7, record.errorMessage());
        ps.setInt(8, record.eventCount());
        ps.setInt(9, record.actionCount());
        ps.setInt(10, record.droppedEvents());
    }

    private static RunRecord map(ResultSet rs) throws SQLException {
        long endedAt = rs.getLong("ended_at");
        boolean endedNull = rs.wasNull();
        return new RunRecord(
                rs.getString("id"),
                rs.getString("script_id"),
                rs.getString("trigger"),
                SqlSupport.toInstant(rs.getLong("started_at")),
                endedNull ? null : SqlSupport.toInstant(endedAt),
                SqlSupport.parseEnum(RunExit.class, rs.getString("exit"), null),
                rs.getString("error_message"),
                rs.getInt("event_count"),
                rs.getInt("action_count"),
                rs.getInt("dropped_events"));
    }
}
