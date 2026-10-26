package com.gmscpt.server.store;

import com.gmscpt.server.domain.HotkeyAction;
import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.HotkeyEvent;
import com.gmscpt.server.domain.Script;
import com.gmscpt.server.domain.ScriptStatus;
import com.gmscpt.server.kernel.input.Accelerator;
import com.gmscpt.server.kernel.input.HotkeyConflictException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code script} / {@code script_revision} / {@code script_hotkey} 三张表的 JDBC 实现。
 *
 * <p><b>正文与元数据分离</b>：Lua 正文的真源是 {@code dataDir/scripts/<id>.lua}（{@link ScriptFileStore}），
 * 数据库只存元数据 + {@code content_hash}。因此本类的读都要过文件、写都要过文件，
 * 并且写路径都遵守同一条纪律：**先在事务内改文件、再改元数据；任何异常都把文件写回旧内容**。
 * 否则会出现「DB 说 rev=2 / hash=B，磁盘上却是 A」这种最难查的错位。
 *
 * <p><b>乐观锁</b>：{@link #update} 用 {@code WHERE id=? AND rev=?} 判定，
 * 影响行数为 0 就说明别人先改了，抛 {@link RevConflictException}，绝不静默覆盖。
 * 整个「读当前 rev → 写文件 → 更新行 → 追加 revision」都在 {@link Database#write} 的
 * 全局写锁里完成，所以进程内不会出现检查与更新之间的竞态窗口。
 *
 * <p><b>几处刻意的语义选择</b>（接口签名没写死的地方）：
 * <ul>
 *   <li>{@link #appendRevision} 的 {@code source} 参数是该 rev 的 <b>Lua 正文快照</b>（与
 *       {@code Script.source} 同名同义），来源说明放 {@code note}（{@code initial}/{@code manual}/{@code ai}）。
 *       没有正文快照就没法"回溯"，而"回溯"正是接口注释里写明这个方法的用途；</li>
 *   <li>{@link #listRevisions} 返回的是给人看/给列表页用的摘要行
 *       （{@code rev · note · 时间 · 字符数}），不是几百 KB 的正文——正文用
 *       {@link #revisions} 或 {@link #revisionSource} 取；</li>
 *   <li>{@link #delete} 是软删除（{@code deleted_at}），正文文件保留，便于事后追查；
 *       真要抹干净用 {@link #purge}；</li>
 *   <li>{@link #updateStatus} 不动 {@code rev}：启停状态不属于内容，改它不该让客户端手里的 rev 失效。</li>
 * </ul>
 */
public final class SqlScriptRepository implements ScriptRepository {

    private static final Logger LOG = Logger.getLogger(SqlScriptRepository.class.getName());

    private static final String COLUMNS =
            "id, name, description, content_hash, rev, status, created_at, updated_at";

    private final Database db;
    private final ScriptFileStore files;

    public SqlScriptRepository(Database db, ScriptFileStore files) {
        this.db = db;
        this.files = files;
    }

    // ------------------------------------------------------------ 查询

    @Override
    public List<Script> findAll() {
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS
                    + " FROM script WHERE deleted_at IS NULL ORDER BY updated_at DESC, id");
                 ResultSet rs = ps.executeQuery()) {
                List<Script> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(connection, rs));
                }
                return out;
            }
        });
    }

    @Override
    public Optional<Script> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS
                    + " FROM script WHERE id = ? AND deleted_at IS NULL")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(connection, rs)) : Optional.<Script>empty();
                }
            }
        });
    }

    // ------------------------------------------------------------ 写入

    @Override
    public Script insert(Script script) {
        String id = script.id();
        String source = script.source() == null ? "" : script.source();
        long rev = Math.max(1L, script.rev());

        // 先留旧正文：元数据写失败时要把文件恢复，避免「库回滚了、文件却已经被改掉」
        Optional<String> previous = files.readIfExists(id);
        files.write(id, source);
        try {
            db.write(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO script(id, name, description, content_hash, rev, status, created_at, updated_at, deleted_at)"
                                + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, NULL)")) {
                    ps.setString(1, id);
                    ps.setString(2, script.name());
                    ps.setString(3, script.description());
                    ps.setString(4, ScriptFileStore.hash(source));
                    ps.setLong(5, rev);
                    ps.setString(6, SqlSupport.nameOf(script.status()));
                    ps.setLong(7, script.createdAt().toEpochMilli());
                    ps.setLong(8, script.updatedAt().toEpochMilli());
                    ps.executeUpdate();
                }
                insertRevision(connection, id, rev, source, "initial");
                return null;
            });
        } catch (RuntimeException failure) {
            restore(id, previous);
            throw failure;
        }
        return findById(id).orElseThrow(() -> new StoreException("脚本写入后读不回来: " + id));
    }

    /** 便捷入口：{@link Script} 记录本身不允许空 id，所以「id 由实现生成」这条语义只能在这里体现。 */
    public Script insertNew(String name, String description, String source) {
        String id = IdGenerator.next();
        return insert(new Script(id, name, description, source, 1L, ScriptStatus.ENABLED, null, null));
    }

    @Override
    public Script update(Script script, long expectedRev) {
        String id = script.id();
        String source = script.source() == null ? "" : script.source();
        String newHash = ScriptFileStore.hash(source);

        AtomicReference<String> previousSource = new AtomicReference<>();
        AtomicBoolean fileWritten = new AtomicBoolean(false);
        try {
            db.write(connection -> {
                long currentRev = readRev(connection, id)
                        .orElseThrow(() -> new NoSuchElementException("脚本不存在: " + id));
                if (currentRev != expectedRev) {
                    throw new RevConflictException(id, expectedRev, currentRev);
                }
                String previous = currentSource(connection, id);
                previousSource.set(previous);

                String currentHash = readHash(connection, id);
                boolean contentChanged = !newHash.equals(currentHash) || !files.exists(id);
                if (contentChanged) {
                    files.write(id, source);
                    fileWritten.set(true);
                }

                long newRev = currentRev + 1;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE script SET name = ?, description = ?, content_hash = ?, rev = ?,"
                                + " status = ?, updated_at = ? WHERE id = ? AND rev = ?")) {
                    ps.setString(1, script.name());
                    ps.setString(2, script.description());
                    ps.setString(3, newHash);
                    ps.setLong(4, newRev);
                    ps.setString(5, SqlSupport.nameOf(script.status() == null ? ScriptStatus.ENABLED : script.status()));
                    ps.setLong(6, System.currentTimeMillis());
                    ps.setString(7, id);
                    ps.setLong(8, expectedRev);
                    if (ps.executeUpdate() == 0) {
                        long actual = readRev(connection, id).orElse(-1L);
                        throw new RevConflictException(id, expectedRev, actual);
                    }
                }
                insertRevision(connection, id, newRev, source, "manual");
                return null;
            });
        } catch (RuntimeException failure) {
            // 事务没提交（或提交失败）时把正文退回旧内容：宁可丢这次编辑，也不能让文件与 rev 错位
            if (fileWritten.get()) {
                restore(id, Optional.ofNullable(previousSource.get()));
            }
            throw failure;
        }
        return findById(id).orElseThrow(() -> new StoreException("脚本更新后读不回来: " + id));
    }

    /**
     * 软删除：置 {@code deleted_at} 并清掉热键绑定。
     * 热键必须一起删——{@code UNIQUE(accelerator, event)} 是全局的，
     * 留着一个「指向已删脚本」的绑定会让那个热键永远绑不上别的脚本。
     * 正文文件保留（配合 {@link #purge} 才是彻底删除）。
     */
    @Override
    public void delete(String id) {
        db.write(connection -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE script SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                ps.setLong(1, now);
                ps.setLong(2, now);
                ps.setString(3, id);
                if (ps.executeUpdate() == 0) {
                    throw new NoSuchElementException("脚本不存在或已删除: " + id);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM script_hotkey WHERE script_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 彻底删除：元数据、历史版本、热键、正文文件一并抹掉（用于测试与「清空回收站」语义）。 */
    public void purge(String id) {
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM script WHERE id = ?")) {
                ps.setString(1, id);
                if (ps.executeUpdate() == 0) {
                    throw new NoSuchElementException("脚本不存在: " + id);
                }
            }
            return null;
        });
        files.delete(id);
    }

    @Override
    public void updateStatus(String id, ScriptStatus status) {
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE script SET status = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                ps.setString(1, SqlSupport.nameOf(status == null ? ScriptStatus.ENABLED : status));
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, id);
                if (ps.executeUpdate() == 0) {
                    throw new NoSuchElementException("脚本不存在: " + id);
                }
            }
            return null;
        });
    }

    @Override
    public void appendRevision(String scriptId, long rev, String source, String note) {
        db.write(connection -> {
            insertRevision(connection, scriptId, rev, source, note);
            return null;
        });
    }

    @Override
    public List<String> listRevisions(String scriptId, int limit) {
        return revisions(scriptId, limit).stream()
                .map(revision -> "rev " + revision.rev()
                        + " · " + (revision.note() == null || revision.note().isBlank() ? "-" : revision.note())
                        + " · " + revision.createdAt()
                        + " · " + (revision.source() == null ? 0 : revision.source().length()) + " 字符")
                .toList();
    }

    // ------------------------------------------------------------ 历史版本（扩展 API）

    /** 历史版本快照（含正文），用于 diff / 回滚。 */
    public record Revision(String id, String scriptId, long rev, String source, String note, Instant createdAt) {
    }

    /** 按 rev 倒序取历史版本（含正文）。 */
    public List<Revision> revisions(String scriptId, int limit) {
        int capped = limit <= 0 ? 20 : Math.min(limit, 1_000);
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, script_id, rev, source, note, created_at FROM script_revision"
                            + " WHERE script_id = ? ORDER BY rev DESC, created_at DESC LIMIT ?")) {
                ps.setString(1, scriptId);
                ps.setInt(2, capped);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Revision> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new Revision(
                                rs.getString("id"),
                                rs.getString("script_id"),
                                rs.getLong("rev"),
                                rs.getString("source"),
                                rs.getString("note"),
                                SqlSupport.toInstant(rs.getLong("created_at"))));
                    }
                    return out;
                }
            }
        });
    }

    /** 取某个 rev 的正文快照（回滚用）。 */
    public Optional<String> revisionSource(String scriptId, long rev) {
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT source FROM script_revision WHERE script_id = ? AND rev = ?"
                            + " ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, scriptId);
                ps.setLong(2, rev);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    // ------------------------------------------------------------ 热键

    @Override
    public List<HotkeyBinding> findHotkeys() {
        return db.read(connection -> selectHotkeys(connection,
                "SELECT id, script_id, accelerator, event, action, enabled FROM script_hotkey"
                        + " ORDER BY accelerator, event", null));
    }

    @Override
    public List<HotkeyBinding> findHotkeysByScript(String scriptId) {
        return db.read(connection -> selectHotkeys(connection,
                "SELECT id, script_id, accelerator, event, action, enabled FROM script_hotkey"
                        + " WHERE script_id = ? ORDER BY accelerator, event", scriptId));
    }

    /**
     * 全量替换某脚本的热键：一个事务里先删后插。
     * 冲突（{@code UNIQUE(accelerator, event)}）时整个事务回滚，
     * 抛 {@link HotkeyConflictException}——**旧绑定原样保留**，不会出现「删了旧的、新的没插进去」的空档。
     */
    @Override
    public void replaceHotkeys(String scriptId, List<HotkeyBinding> bindings) {
        List<HotkeyBinding> wanted = bindings == null ? List.of() : bindings;
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM script_hotkey WHERE script_id = ?")) {
                ps.setString(1, scriptId);
                ps.executeUpdate();
            }
            Set<String> seen = new HashSet<>();
            for (HotkeyBinding binding : wanted) {
                String accelerator = normalizeAccelerator(binding.accelerator());
                String event = SqlSupport.nameOf(binding.event() == null ? HotkeyEvent.KEY_PRESSED : binding.event());
                if (!seen.add(accelerator + "|" + event)) {
                    // 同一批里自己重复：也是冲突，交给调用方改
                    throw new HotkeyConflictException(accelerator, scriptId, scriptId);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO script_hotkey(id, script_id, accelerator, event, action, enabled)"
                                + " VALUES(?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, binding.id() == null || binding.id().isBlank() ? IdGenerator.next() : binding.id());
                    ps.setString(2, scriptId);
                    ps.setString(3, accelerator);
                    ps.setString(4, event);
                    ps.setString(5, SqlSupport.nameOf(binding.action() == null ? HotkeyAction.TOGGLE : binding.action()));
                    ps.setInt(6, binding.enabled() ? 1 : 0);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (SqlSupport.isUniqueViolation(e)) {
                        throw new HotkeyConflictException(accelerator, ownerOf(connection, accelerator, event), scriptId);
                    }
                    throw e;
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------ 内部

    private List<HotkeyBinding> selectHotkeys(Connection connection, String sql, String scriptId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (scriptId != null) {
                ps.setString(1, scriptId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<HotkeyBinding> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new HotkeyBinding(
                            rs.getString("id"),
                            rs.getString("script_id"),
                            rs.getString("accelerator"),
                            SqlSupport.parseEnum(HotkeyEvent.class, rs.getString("event"), HotkeyEvent.KEY_PRESSED),
                            SqlSupport.parseEnum(HotkeyAction.class, rs.getString("action"), HotkeyAction.TOGGLE),
                            rs.getInt("enabled") != 0));
                }
                return out;
            }
        }
    }

    private Script map(Connection connection, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        return new Script(
                id,
                rs.getString("name"),
                rs.getString("description"),
                currentSource(connection, id),
                rs.getLong("rev"),
                SqlSupport.parseEnum(ScriptStatus.class, rs.getString("status"), ScriptStatus.ENABLED),
                SqlSupport.toInstant(rs.getLong("created_at")),
                SqlSupport.toInstant(rs.getLong("updated_at")));
    }

    /**
     * 当前正文：文件优先；文件被手工删掉/磁盘丢文件时退回最新 revision 快照。
     * 这是纯读路径——不回写文件，避免一次「查列表」变成 N 次磁盘写。
     */
    private String currentSource(Connection connection, String scriptId) throws SQLException {
        Optional<String> fromFile = files.readIfExists(scriptId);
        if (fromFile.isPresent()) {
            return fromFile.get();
        }
        Optional<String> snapshot = latestRevisionSource(connection, scriptId);
        if (snapshot.isPresent()) {
            LOG.warning("脚本 " + scriptId + " 的正文文件缺失，已用最新历史版本兜底");
            return snapshot.get();
        }
        return "";
    }

    private Optional<String> latestRevisionSource(Connection connection, String scriptId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT source FROM script_revision WHERE script_id = ? ORDER BY rev DESC, created_at DESC LIMIT 1")) {
            ps.setString(1, scriptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private Optional<Long> readRev(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT rev FROM script WHERE id = ? AND deleted_at IS NULL")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    private String readHash(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT content_hash FROM script WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void insertRevision(Connection connection, String scriptId, long rev, String source, String note)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO script_revision(id, script_id, rev, source, note, created_at) VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, IdGenerator.next());
            ps.setString(2, scriptId);
            ps.setLong(3, rev);
            ps.setString(4, source);
            ps.setString(5, note);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** 冲突时问一句「这条热键现在归谁」，好让异常信息里带上真正的占用者。 */
    private String ownerOf(Connection connection, String accelerator, String event) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT script_id FROM script_hotkey WHERE accelerator = ? AND event = ? LIMIT 1")) {
            ps.setString(1, accelerator);
            ps.setString(2, event);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "unknown";
            }
        } catch (SQLException e) {
            LOG.log(Level.FINE, "查询热键占用者失败", e);
            return "unknown";
        }
    }

    /**
     * 统一成 {@link Accelerator} 的规范形式（小写、修饰键固定顺序）。
     * 不规范化的话 {@code ctrl+shift+f8} 与 {@code shift+ctrl+f8} 会被 UNIQUE 当成两个键，
     * 冲突检测形同虚设。解析不了的（未知键名）退回「去空白 + 小写」，不因为一个怪名字就拒绝落库。
     */
    private static String normalizeAccelerator(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Accelerator.parse(raw).canonical();
        } catch (RuntimeException e) {
            return raw.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** 把正文文件恢复成给定内容；恢复失败只告警（此时数据库是权威，文件可由 revision 快照重建）。 */
    private void restore(String id, Optional<String> previous) {
        try {
            if (previous.isPresent()) {
                files.write(id, previous.get());
            } else {
                files.delete(id);
            }
        } catch (RuntimeException restoreFailure) {
            LOG.log(Level.WARNING, "回滚脚本正文文件失败: " + id, restoreFailure);
        }
    }
}
