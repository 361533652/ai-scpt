package com.gmscpt.server.store;

import com.gmscpt.server.domain.LogEntry;
import com.gmscpt.server.domain.LogLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link LogRepository} 的异步实现：调用方线程只做一次入队，磁盘 IO 全部交给单写线程。
 *
 * <p><b>为什么必须异步</b>：{@code append} 的调用者包括全局键鼠钩子线程和 Lua 脚本线程。
 * 钩子回调一旦被磁盘写阻塞，Windows 会摘钩（旧版就有这个毛病）；脚本线程被阻塞则整个宏卡住。
 * 所以这里：{@code append} = {@code ArrayBlockingQueue.offer}（O(1)、无锁竞争、不分配大对象），
 * 队列满直接丢弃并计数（{@link #droppedCount()}），**永不阻塞、永不抛异常**。
 *
 * <p><b>批量策略</b>：单写线程一次最多取 {@value #BATCH_SIZE} 条，在一个事务里
 * {@code addBatch/executeBatch} 提交；没有新数据时最长等 {@value #FLUSH_INTERVAL_MS} ms
 * 就把手上的一小批先落盘，保证「日志页面刷新就能看到」而不是攒够 200 条才可见。
 *
 * <p><b>读取的可见性</b>：{@link #query} 把「已落盘的行」和「还在内存里的尾部」
 * （队列里的 + 写线程手上正在提交的那一批）合并、按 seq 去重后再排序。
 * 这样刚写进去还没落盘的日志也能被查到，不出现「界面比实际慢半拍」。
 * 这是控制面的调用（HTTP 线程），允许读盘——只有 {@code append} 是无 IO 热路径。
 */
public final class AsyncLogRepository implements LogRepository, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AsyncLogRepository.class.getName());

    /** 默认队列容量：约等于「落盘完全停摆时能抗住的日志条数」，够一次故障排查用。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 8192;

    /** 单批上限：再大单事务耗时变长，反而拖慢可见性。 */
    private static final int BATCH_SIZE = 200;

    /** 空闲时的最大刷盘延迟。 */
    private static final long FLUSH_INTERVAL_MS = 500L;

    private final Database db;
    private final BlockingQueue<LogEntry> queue;
    private final int capacity;
    private final Thread writer;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** 写线程当前正在提交的批次；{@link #query} 靠它消除「已出队但未落盘」的盲区。 */
    private volatile List<LogEntry> inFlight = List.of();

    public AsyncLogRepository(Database db) {
        this(db, DEFAULT_QUEUE_CAPACITY);
    }

    public AsyncLogRepository(Database db, int queueCapacity) {
        if (db == null) {
            throw new IllegalArgumentException("db 不能为空");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity 必须 >= 1");
        }
        this.db = db;
        this.capacity = queueCapacity;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.writer = new Thread(this::writerLoop, "gmscpt-log-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    // ------------------------------------------------------------ 热路径

    /** 绝不阻塞、绝不抛异常：日志写不进去可以丢，脚本/钩子不能因此卡住。 */
    @Override
    public void append(LogEntry entry) {
        if (entry == null) {
            return;
        }
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
        }
    }

    // ------------------------------------------------------------ 查询

    @Override
    public List<LogEntry> query(String scriptId, String level, long sinceSeq, int limit) {
        int capped = limit <= 0 ? 200 : Math.min(limit, 5_000);
        List<LogEntry> committed = db.read(connection ->
                selectFromDb(connection, scriptId, level, sinceSeq, capped));

        // 内存尾部：队列里没落盘的 + 写线程手上正在提交的那一批
        List<LogEntry> pending = new ArrayList<>(queue);
        pending.addAll(inFlight);

        Set<LogEntry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<LogEntry> merged = new ArrayList<>(committed.size() + pending.size());
        for (LogEntry entry : committed) {
            if (seen.add(entry)) {
                merged.add(entry);
            }
        }
        for (LogEntry entry : pending) {
            if (matches(entry, scriptId, level, sinceSeq) && seen.add(entry)) {
                merged.add(entry);
            }
        }
        merged.sort((a, b) -> {
            int bySeq = Long.compare(a.seq(), b.seq());
            return bySeq != 0 ? bySeq : Long.compare(a.timeMillis(), b.timeMillis());
        });
        return merged.size() > capped ? new ArrayList<>(merged.subList(0, capped)) : merged;
    }

    /**
     * 保留策略：删除早于给定时间戳的日志。
     *
     * <p>先 flush 再删：否则「已经在内存里、马上要落盘」的旧日志会在 DELETE 之后被写回来。
     * 这是维护路径（定时任务调用），允许在调用线程上做 IO；热路径只有 {@link #append}。
     *
     * @return 实际删除的行数
     */
    @Override
    public int deleteOlderThan(long epochMillis) {
        flushNow();
        awaitWriterIdle(1_000L);
        return db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM log WHERE ts < ?")) {
                ps.setLong(1, epochMillis);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------ 监控与关停

    /** 因队列满而丢弃的条数（指标 {@code logs_dropped_total} 的来源）。 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 已成功落盘的条数。 */
    public long writtenCount() {
        return written.get();
    }

    /** 落盘失败的条数（磁盘满/库被关）。日志链路故障只告警，不影响脚本。 */
    public long failedCount() {
        return failed.get();
    }

    public int pendingCount() {
        return queue.size();
    }

    public int queueCapacity() {
        return capacity;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        writer.interrupt();
        try {
            writer.join(3_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushNow(); // 双保险：writer 被 join 超时截断时，这里把剩下的补写完
        LOG.info(() -> "日志写线程已停止：落盘 " + written.get() + " 条，丢弃 " + dropped.get()
                + " 条，失败 " + failed.get() + " 条");
    }

    // ------------------------------------------------------------ 内部

    private void writerLoop() {
        while (running.get()) {
            LogEntry first;
            try {
                first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            List<LogEntry> batch = new ArrayList<>(BATCH_SIZE);
            if (first != null) {
                batch.add(first);
            }
            queue.drainTo(batch, BATCH_SIZE - batch.size());
            if (batch.isEmpty()) {
                continue;
            }
            writeBatch(batch);
        }
        flushNow();
    }

    /** 在调用线程上把队列里现有的条目全部写掉（关停与保留策略用，不是热路径）。 */
    private void flushNow() {
        while (true) {
            List<LogEntry> batch = new ArrayList<>(BATCH_SIZE);
            if (queue.drainTo(batch, BATCH_SIZE) == 0) {
                return;
            }
            writeBatch(batch);
        }
    }

    /** 一批一个事务：{@code INSERT OR IGNORE} + seq 的部分唯一索引，重复落盘天然幂等。 */
    private void writeBatch(List<LogEntry> batch) {
        inFlight = batch;
        try {
            db.write(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO log(seq, ts, level, source, script_id, run_id, message)"
                                + " VALUES(?, ?, ?, ?, ?, ?, ?)")) {
                    for (LogEntry entry : batch) {
                        ps.setLong(1, entry.seq());
                        ps.setLong(2, entry.timeMillis());
                        ps.setString(3, SqlSupport.nameOf(entry.level()));
                        ps.setString(4, entry.source());
                        ps.setString(5, entry.scriptId());
                        ps.setString(6, entry.runId());
                        ps.setString(7, entry.message());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
            written.addAndGet(batch.size());
        } catch (Throwable failure) {
            failed.addAndGet(batch.size());
            LOG.log(Level.WARNING, "日志批量落盘失败，丢弃 " + batch.size() + " 条", failure);
        } finally {
            inFlight = List.of();
        }
    }

    private void awaitWriterIdle(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static List<LogEntry> selectFromDb(Connection connection, String scriptId, String level,
                                               long sinceSeq, int limit) throws SQLException {
        // 只拼接固定的条件片段，所有取值一律走占位符
        StringBuilder sql = new StringBuilder(
                "SELECT seq, ts, level, source, script_id, run_id, message FROM log WHERE 1 = 1");
        List<Object> params = new ArrayList<>(4);
        if (scriptId != null && !scriptId.isBlank()) {
            sql.append(" AND script_id = ?");
            params.add(scriptId);
        }
        if (level != null && !level.isBlank()) {
            sql.append(" AND level = ?");
            params.add(level.trim().toUpperCase(Locale.ROOT));
        }
        if (sinceSeq > 0L) {
            sql.append(" AND seq > ?");
            params.add(sinceSeq);
        }
        sql.append(" ORDER BY seq ASC, ts ASC LIMIT ?");
        params.add(limit);

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<LogEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new LogEntry(
                            rs.getLong("seq"),
                            rs.getLong("ts"),
                            SqlSupport.parseEnum(LogLevel.class, rs.getString("level"), LogLevel.INFO),
                            rs.getString("source"),
                            rs.getString("script_id"),
                            rs.getString("run_id"),
                            rs.getString("message")));
                }
                return out;
            }
        }
    }

    private static boolean matches(LogEntry entry, String scriptId, String level, long sinceSeq) {
        if (entry.seq() <= sinceSeq) {
            return false;
        }
        if (scriptId != null && !scriptId.isBlank() && !scriptId.equals(entry.scriptId())) {
            return false;
        }
        return level == null || level.isBlank() || level.trim().equalsIgnoreCase(SqlSupport.nameOf(entry.level()));
    }
}
