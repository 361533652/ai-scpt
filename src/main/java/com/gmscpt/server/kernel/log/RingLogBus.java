package com.gmscpt.server.kernel.log;

import com.gmscpt.server.config.GmScriptProperties;
import com.gmscpt.server.domain.LogEntry;
import com.gmscpt.server.domain.LogLevel;
import com.gmscpt.server.store.LogRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 内存环形缓冲 + 订阅推送的日志总线。
 *
 * <p>缓冲固定 5000 条（旧版 ConsolePanel 也是 5000，但那条日志只活在 Swing 组件里，
 * 刷新即失、无法导出）。落库走 {@link LogRepository} 的异步实现，绝不阻塞调用方。
 */
public class RingLogBus implements LogBus {

    private static final int CAPACITY = 5000;

    private final LogEntry[] ring = new LogEntry[CAPACITY];
    private final AtomicLong seq = new AtomicLong();
    private final Object lock = new Object();
    private long count;

    private final CopyOnWriteArrayList<Consumer<LogEntry>> subscribers = new CopyOnWriteArrayList<>();
    private final LogRepository repository;
    private final GmScriptProperties props;

    public RingLogBus(LogRepository repository, GmScriptProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Override
    public void publish(LogLevel level, String source, String scriptId, String runId, String message) {
        long s = seq.incrementAndGet();
        LogEntry entry = new LogEntry(s, System.currentTimeMillis(), level, source, scriptId, runId, message);
        synchronized (lock) {
            // seq 从 1 开始，槽位必须用 (seq - 1)，否则下标整体错一位，
            // 表现为「最新一条日志永远取不到」——这正是本类第一版的 bug，由回归测试抓到。
            ring[(int) ((s - 1) % CAPACITY)] = entry;
            count++;
        }
        if (repository != null) {
            repository.append(entry);
        }
        for (Consumer<LogEntry> sub : subscribers) {
            try {
                sub.accept(entry);
            } catch (Throwable ignored) {
                // 订阅者的异常不能影响日志主链路（SSE 连接断开是常态）
            }
        }
    }

    @Override
    public List<LogEntry> recent(int limit) {
        synchronized (lock) {
            long total = count;
            int n = (int) Math.min(limit <= 0 ? 200 : limit, Math.min(total, CAPACITY));
            List<LogEntry> out = new ArrayList<>(n);
            // 序号 1..total 对应槽位 (seq-1)，取最后 n 条：total-n+1 .. total
            for (long index = total - n + 1; index <= total; index++) {
                LogEntry e = ring[(int) ((index - 1) % CAPACITY)];
                if (e != null) out.add(e);
            }
            return out;
        }
    }

    @Override
    public List<LogEntry> since(long sinceSeq, int limit) {
        synchronized (lock) {
            long from = Math.max(sinceSeq + 1, count - CAPACITY + 1);
            List<LogEntry> out = new ArrayList<>();
            for (long index = from; index <= count; index++) {
                LogEntry e = ring[(int) ((index - 1) % CAPACITY)];
                if (e != null) out.add(e);
                if (out.size() >= limit) break;
            }
            return out;
        }
    }

    @Override
    public long lastSeq() {
        return seq.get();
    }

    @Override
    public AutoCloseable subscribe(Consumer<LogEntry> consumer) {
        subscribers.add(consumer);
        return () -> subscribers.remove(consumer);
    }
}
