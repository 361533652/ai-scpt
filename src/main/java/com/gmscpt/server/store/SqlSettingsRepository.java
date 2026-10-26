package com.gmscpt.server.store;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code setting} / {@code setting_secret} 两张表的 JDBC 实现。
 *
 * <p>为什么分两张表：
 * <ul>
 *   <li>{@code setting} 存普通配置（JSON 文本或裸标量），可以整表读出来给前端；
 *       接口层的 {@code GET /settings} 只回 {@code hasApiKey}，所以密钥**绝不能**住在这张表里——
 *       只要它在，早晚会有人写一句 {@code SELECT *} 把它带出去；</li>
 *   <li>{@code setting_secret} 只存密文（BLOB），走 {@link SecretBox}（AES-256-GCM，密钥在
 *       {@code dataDir/secret.key}）。写进去的明文不落盘、不进日志；</li>
 *   <li>读密钥失败（密钥轮换/文件损坏）返回 {@code null} 并告警，不抛异常——
 *       配置读不出来的后果应该是「AI 用不了」，而不是「整个服务起不来」。</li>
 * </ul>
 */
public final class SqlSettingsRepository implements SettingsRepository {

    private static final Logger LOG = Logger.getLogger(SqlSettingsRepository.class.getName());

    /** 密钥文件名，与数据库同目录，随数据目录一起备份/迁移。 */
    public static final String KEY_FILE_NAME = "secret.key";

    private final Database db;
    private final SecretBox secretBox;

    public SqlSettingsRepository(Database db) {
        this.db = db;
        this.secretBox = new SecretBox(secretBoxPath(db));
    }

    private static Path secretBoxPath(Database db) {
        return db.dataDir().resolve(KEY_FILE_NAME);
    }

    @Override
    public Map<String, String> loadAll() {
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT key, value_json FROM setting ORDER BY key");
                 ResultSet rs = ps.executeQuery()) {
                Map<String, String> out = new LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
                return out;
            }
        });
    }

    @Override
    public void put(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("设置项 key 不能为空");
        }
        putAll(Map.of(key, value == null ? "" : value));
    }

    @Override
    public void putAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        db.write(connection -> {
            // UPSERT：设置项是「有则覆盖」，用一条语句解决并发下的读改写竞态
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO setting(key, value_json, updated_at) VALUES(?, ?, ?)"
                            + " ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json,"
                            + " updated_at = excluded.updated_at")) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    ps.setString(1, entry.getKey());
                    ps.setString(2, entry.getValue());
                    ps.setLong(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public void putSecret(String key, String value) {
        if (value == null || value.isBlank()) {
            // 清空即删除：留着空密文只会让 hasSecret 判定含糊
            db.write(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM setting_secret WHERE key = ?")) {
                    ps.setString(1, key);
                    ps.executeUpdate();
                }
                return null;
            });
            return;
        }
        byte[] sealed = secretBox.seal(value);
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO setting_secret(key, value, updated_at) VALUES(?, ?, ?)"
                            + " ON CONFLICT(key) DO UPDATE SET value = excluded.value,"
                            + " updated_at = excluded.updated_at")) {
                ps.setString(1, key);
                ps.setBytes(2, sealed);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean hasSecret(String key) {
        return db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM setting_secret WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public String getSecret(String key) {
        byte[] blob = db.read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT value FROM setting_secret WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes(1) : null;
                }
            }
        });
        if (blob == null) {
            return null;
        }
        try {
            return secretBox.open(blob);
        } catch (StoreException e) {
            LOG.log(Level.WARNING, "读取密钥失败，按「未配置」处理: " + key, e);
            return null;
        }
    }

    /** 删除一个普通设置项（接口层的 PUT /settings 支持「置空」语义时需要）。 */
    public void remove(String key) {
        db.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM setting WHERE key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 密钥文件路径（备份/迁移时要带上）。 */
    public Path keyFile() {
        return secretBoxPath(db);
    }
}
