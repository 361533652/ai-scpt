package com.gmscpt.server.store;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 连接与架构（schema）的唯一入口：负责建目录、开库、PRAGMA、版本化迁移与连接池。
 *
 * <p><b>为什么自己做连接池</b>：约束是「不加 HikariCP 这类新依赖」，而 SQLite 的 JDBC 连接
 * 不能多线程共享使用（同一 Connection 上并发执行语句会互相踩状态）。
 * 于是这里做一个极简的池：借出 {@link #open()}，归还靠 {@code close()}（通过动态代理拦下），
 * 连接复用、句柄有上限、不泄漏。懒创建——没并发就不占句柄。
 *
 * <p><b>写入串行化</b>：SQLite 在任一时刻只允许一个写事务。池里多连接并发写只会换来
 * SQLITE_BUSY 重试，所以 {@link #write} 用一把进程内的公平锁把写事务排成队；
 * 读走 WAL，不阻塞写（这也是必须开 WAL 的原因）。
 *
 * <p><b>迁移</b>：{@code schema_version(version, applied_at)} 记录已应用版本，
 * {@link #MIGRATIONS} 是各版本 DDL 列表，缺失的版本在一个事务里按序补齐。
 * 不引 Flyway——迁移脚本就这么点，一个 List 足够。
 *
 * <p><b>启动自愈</b>：库为空时从 {@code /knowledge/knowledge.json} 导入种子条目（见 {@link KnowledgeSeeder}），
 * 解析失败只告警、绝不阻断启动。
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    /** 库文件名，固定放在 dataDir 下。 */
    public static final String DB_FILE_NAME = "gmscpt.db";

    private static final int DEFAULT_MAX_CONNECTIONS = 8;
    private static final long BORROW_TIMEOUT_MS = 5_000L;

    /**
     * 版本化 DDL。**只允许追加**，不允许改历史版本——
     * 已经升过级的库不会重跑旧脚本，改旧脚本只会让新老库结构分叉。
     *
     * <p>列名与 docs/backend-architecture.md 第四节基本一致，几处刻意的微调：
     * <ul>
     *   <li>{@code run} 表补 {@code dropped_events}——domain 的 {@code RunRecord}
     *       有这个字段（事件队列溢出计数），架构文档的表结构漏了；</li>
     *   <li>{@code chat_message} 用 {@code provider_id}（与 {@code ChatMessage.providerId} 同名），
     *       架构文档写的是 {@code provider}；</li>
     *   <li>{@code log} 表不存 {@code data_json}（domain 的 {@code LogEntry} 没有扩展字段，
     *       留一个永远为 NULL 的列只会误导人），另外给 {@code seq} 加了「> 0 时唯一」的部分索引，
     *       让批次重放可以安全地用 INSERT OR IGNORE 幂等化；</li>
     *   <li>{@code script_revision.source} 存该 rev 的 Lua 正文快照（回溯要用）。
     *       正文真源仍是文件，这里只是历史存档。</li>
     *   <li>{@code run} / {@code log} 的 {@code script_id} **刻意不建外键**：
     *       脚本被 purge 之后，历史运行与日志仍应可查（否则排查「删掉的那个脚本当初为什么崩」
     *       就成了不可能）；外键只用在必须级联的地方（revision/hotkey/message）。</li>
     *   <li>{@code chat_message} 额外用自增 {@code seq} 当主键、{@code id} 唯一：
     *       消息顺序必须按插入序稳定，而毫秒时间戳在流式对话里会重复。</li>
     * </ul>
     */
    private static final List<String> MIGRATIONS = List.of(
            """
            CREATE TABLE script (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                description   TEXT,
                content_hash  TEXT,
                rev           INTEGER NOT NULL DEFAULT 1,
                status        TEXT    NOT NULL DEFAULT 'ENABLED',
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                deleted_at    INTEGER
            ) WITHOUT ROWID;
            CREATE INDEX idx_script_updated ON script(updated_at DESC);

            CREATE TABLE script_revision (
                id         TEXT PRIMARY KEY,
                script_id  TEXT    NOT NULL REFERENCES script(id) ON DELETE CASCADE,
                rev        INTEGER NOT NULL,
                source     TEXT,
                note       TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX idx_revision_script ON script_revision(script_id, rev DESC);

            CREATE TABLE script_hotkey (
                id          TEXT PRIMARY KEY,
                script_id   TEXT    NOT NULL REFERENCES script(id) ON DELETE CASCADE,
                accelerator TEXT    NOT NULL,
                event       TEXT    NOT NULL,
                action      TEXT    NOT NULL,
                enabled     INTEGER NOT NULL DEFAULT 1,
                UNIQUE(accelerator, event)
            );
            CREATE INDEX idx_hotkey_script ON script_hotkey(script_id);

            CREATE TABLE run (
                id             TEXT PRIMARY KEY,
                script_id      TEXT,
                trigger        TEXT,
                started_at     INTEGER NOT NULL,
                ended_at       INTEGER,
                exit           TEXT,
                error_message  TEXT,
                event_count    INTEGER NOT NULL DEFAULT 0,
                action_count   INTEGER NOT NULL DEFAULT 0,
                dropped_events INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX idx_run_script ON run(script_id, started_at DESC);
            CREATE INDEX idx_run_started ON run(started_at DESC);

            CREATE TABLE log (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                seq       INTEGER,
                ts        INTEGER NOT NULL,
                level     TEXT    NOT NULL,
                source    TEXT,
                script_id TEXT,
                run_id    TEXT,
                message   TEXT
            );
            CREATE INDEX idx_log_run ON log(run_id, ts);
            CREATE INDEX idx_log_ts ON log(ts);
            CREATE UNIQUE INDEX idx_log_seq ON log(seq) WHERE seq IS NOT NULL AND seq > 0;

            CREATE TABLE chat_session (
                id          TEXT PRIMARY KEY,
                title       TEXT NOT NULL,
                provider_id TEXT,
                model       TEXT,
                created_at  INTEGER NOT NULL
            );

            CREATE TABLE chat_message (
                seq         INTEGER PRIMARY KEY AUTOINCREMENT,
                id          TEXT NOT NULL UNIQUE,
                session_id  TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
                role        TEXT NOT NULL,
                content     TEXT,
                thinking    TEXT,
                tokens_in   INTEGER NOT NULL DEFAULT 0,
                tokens_out  INTEGER NOT NULL DEFAULT 0,
                provider_id TEXT,
                model       TEXT,
                created_at  INTEGER NOT NULL
            );
            CREATE INDEX idx_message_session ON chat_message(session_id, seq);

            CREATE TABLE kb_entry (
                id           TEXT PRIMARY KEY,
                game         TEXT,
                title        TEXT NOT NULL,
                description  TEXT,
                script       TEXT,
                tags_json    TEXT,
                source       TEXT,
                enabled      INTEGER NOT NULL DEFAULT 1,
                content_hash TEXT,
                updated_at   INTEGER NOT NULL
            );
            CREATE INDEX idx_kb_updated ON kb_entry(updated_at DESC);

            CREATE TABLE setting (
                key        TEXT PRIMARY KEY,
                value_json TEXT,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE setting_secret (
                key        TEXT PRIMARY KEY,
                value      BLOB    NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """);

    private final Path dataDir;
    private final Path dbFile;
    private final String url;
    private final int maxConnections;

    private final BlockingQueue<Connection> idle = new LinkedBlockingQueue<>();
    private final Object poolLock = new Object();
    private final ReentrantLock writeLock = new ReentrantLock(true);

    private int created;
    private boolean closed;

    public Database(Path dataDir) {
        this(dataDir, DEFAULT_MAX_CONNECTIONS);
    }

    public Database(Path dataDir, int maxConnections) {
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir 不能为空");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections 必须 >= 1");
        }
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.maxConnections = maxConnections;
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            throw new StoreException("无法创建数据目录: " + this.dataDir, e);
        }
        this.dbFile = this.dataDir.resolve(DB_FILE_NAME);
        // sqlite-jdbc 接受 Windows 路径，但反斜杠在 URL 里语义含糊，统一转正斜杠
        this.url = "jdbc:sqlite:" + dbFile.toString().replace('\\', '/');
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new StoreException("类路径上找不到 sqlite-jdbc 驱动", e);
        }
        migrate();
        seedKnowledge();
    }

    // ------------------------------------------------------------ 对外 API

    /** 借一个连接；务必用 try-with-resources，{@code close()} 会把它还回池而不是真关。 */
    public Connection open() {
        return wrap(borrow());
    }

    /**
     * 读路径：不占写锁，可在多个线程并发调用（WAL 下读不阻塞写）。
     */
    public <T> T read(Work<T> work) {
        try (Connection connection = open()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new StoreException("数据库读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写路径：进程内全局串行 + 自动事务。
     * 抛异常自动回滚；业务异常（乐观锁冲突、热键冲突）原样上抛。
     *
     * <p>不要在 {@code write} 里再调 {@code write}：外层持锁、内层另开连接会自己把自己锁死。
     */
    public <T> T write(Work<T> work) {
        writeLock.lock();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Throwable failure) {
                // 回滚 + 状态复位：脏事务绝不能跟着连接回到池子里
                resetAfterFailure(connection);
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new StoreException("写事务失败: " + failure.getMessage(), failure);
            } finally {
                try {
                    if (!connection.getAutoCommit()) {
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException ignored) {
                    // 归还前的状态复位失败无需上报：连接会被复用，autocommit=true 是默认值
                }
            }
        } catch (SQLException e) {
            throw new StoreException("数据库写入失败: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path dbFile() {
        return dbFile;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public boolean isClosed() {
        synchronized (poolLock) {
            return closed;
        }
    }

    @Override
    public void close() {
        List<Connection> remaining = new ArrayList<>();
        synchronized (poolLock) {
            if (closed) {
                return;
            }
            closed = true;
            idle.drainTo(remaining);
        }
        for (Connection connection : remaining) {
            physicalClose(connection);
        }
        LOG.info(() -> "SQLite 已关闭: " + dbFile);
    }

    /** 执行单元；允许抛 {@link SQLException}，由 {@link #read}/{@link #write} 统一收口。 */
    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    // ------------------------------------------------------------ 迁移与种子

    private void migrate() {
        write(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                        + "version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            }
            int current = currentVersion(connection);
            int target = MIGRATIONS.size();
            for (int version = current + 1; version <= target; version++) {
                for (String ddl : splitStatements(MIGRATIONS.get(version - 1))) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(ddl);
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO schema_version(version, applied_at) VALUES(?, ?)")) {
                    ps.setInt(1, version);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            if (current >= target) {
                LOG.fine(() -> "SQLite 架构已是最新版本 v" + target);
            } else {
                LOG.info(() -> "SQLite 架构已迁移: v" + current + " -> v" + target + " (" + dbFile + ")");
            }
            return null;
        });
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * 按 {@code ;} 拆多语句。DDL 文本里没有字符串字面量包含分号（默认值都是简单字面量），
     * 所以裸拆是安全的；拆完保留换行，行尾 {@code --} 注释依旧有效。
     */
    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        for (String part : script.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    /** 首次初始化（kb_entry 为空）时导入种子；任何失败都只告警，不影响启动。 */
    private void seedKnowledge() {
        try {
            int imported = KnowledgeSeeder.seedIfEmpty(this);
            if (imported > 0) {
                LOG.info(() -> "知识库为空，已导入 " + imported + " 条种子条目");
            }
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "知识库种子导入失败，已跳过（不影响启动）", failure);
        }
    }

    // ------------------------------------------------------------ 连接池

    private Connection borrow() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BORROW_TIMEOUT_MS);
        while (true) {
            synchronized (poolLock) {
                if (closed) {
                    throw new StoreException("Database 已关闭");
                }
                Connection pooled = idle.poll();
                if (pooled != null && !isUsable(pooled)) {
                    // 物理上已失效的连接（外部 rm 掉库文件、驱动内部错误后自关）不能还给调用方
                    physicalClose(pooled);
                    pooled = null;
                }
                if (pooled != null) {
                    return pooled;
                }
                if (created < maxConnections) {
                    created++;
                    try {
                        return newConnection();
                    } catch (SQLException e) {
                        created--;
                        throw new StoreException("打开 SQLite 连接失败: " + e.getMessage(), e);
                    } catch (RuntimeException e) {
                        created--;
                        throw e;
                    }
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new StoreException("SQLite 连接池繁忙：上限 " + maxConnections + " 个连接全部被占用");
            }
            try {
                Connection pooled = idle.poll(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                if (pooled != null) {
                    return pooled;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException("等待数据库连接时被中断", e);
            }
        }
    }

    private Connection newConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        boolean ready = false;
        try (Statement statement = connection.createStatement()) {
            // SQLite 的 PRAGMA 只对当前连接生效，所以每建一条连接都要重新设一遍
            statement.execute("PRAGMA busy_timeout=5000");  // 与外部进程争锁时不立刻 SQLITE_BUSY
            statement.execute("PRAGMA foreign_keys=ON");    // 会话删除级联到消息，依赖它
            statement.execute("PRAGMA journal_mode=WAL");   // 读写并发；数据库级设置，重复执行幂等
            statement.execute("PRAGMA synchronous=NORMAL"); // WAL 下已能保证崩溃一致性，比 FULL 快一个量级
            ready = true;
        } finally {
            if (!ready) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 初始化失败的连接直接丢弃
                }
            }
        }
        connection.setAutoCommit(true);
        return connection;
    }

    /**
     * 失败后复位连接状态：把未提交的事务回滚，再恢复 autocommit。
     * 不做这一步的话，「事务中途抛异常 → 连接带着未提交事务被还回池」，
     * 下个借用者会在别人的事务里执行语句，直到某次 commit 把脏数据一起提交。
     */
    private static void resetAfterFailure(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.log(Level.FINE, "复位 SQLite 连接状态失败", e);
        }
    }

    private Connection wrap(Connection real) {
        AtomicBoolean released = new AtomicBoolean(false);
        return (Connection) Proxy.newProxyInstance(
                Database.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "close" -> {
                            release(real, released);
                            return null;
                        }
                        case "isClosed" -> {
                            return released.get() || real.isClosed();
                        }
                        case "toString" -> {
                            return "PooledConnection[" + real + "]";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == (args == null || args.length == 0 ? null : args[0]);
                        }
                        default -> {
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void release(Connection real, AtomicBoolean released) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        synchronized (poolLock) {
            if (closed) {
                physicalClose(real);
                return;
            }
            idle.offer(real);
        }
    }

    /** 池里躺久的连接可能已被驱动标记为关闭（例如库文件被外部删掉后触发内部错误）；借出前体检。 */
    private static boolean isUsable(Connection connection) {
        try {
            return !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private void physicalClose(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.log(Level.FINE, "关闭 SQLite 连接失败", e);
        } finally {
            synchronized (poolLock) {
                created--;
            }
        }
    }
}
