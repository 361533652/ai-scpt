package com.gmscpt.server.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * store 包内部共用的小工具：时间、枚举、正文指纹、约束冲突判定的唯一实现。
 * 包私有——仓库之间共享，不对外暴露 API。
 */
final class SqlSupport {

    private SqlSupport() {
    }

    /**
     * 时间统一存 epoch millis（INTEGER）。
     * 不存字符串：SQLite 没有原生日期类型，字符串时间一旦时区/格式不统一就没法比较排序，
     * 而 long 可以直接比大小、加索引、跨语言解析。
     */
    static Long toMillis(Instant time) {
        return time == null ? null : time.toEpochMilli();
    }

    static Instant toInstant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    /** 枚举一律存 {@code name()} 字符串（可读、可手查数据库）；读回时无法识别则回退默认值，不让脏数据炸掉整表读取。 */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 正文指纹（SHA-256 十六进制）：判断「文件内容是否真的变了」，避免无意义重写与时间戳漂移。 */
    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 判定「唯一约束冲突」。
     *
     * <p>刻意不去引用 sqlite-jdbc 的 {@code SQLiteErrorCode} 常量类，改用
     * {@link SQLException#getErrorCode()} 加 SQLite 固定文案双保险：
     * 驱动升级或换实现时不会因为常量类签名变化而编译失败。
     * 19 = SQLITE_CONSTRAINT，1555 = SQLITE_CONSTRAINT_PRIMARYKEY，2067 = SQLITE_CONSTRAINT_UNIQUE。
     */
    static boolean isUniqueViolation(SQLException e) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            int code = cur.getErrorCode();
            if (code == 19 || code == 1555 || code == 2067) {
                return true;
            }
            String message = cur.getMessage();
            if (message != null && message.contains("UNIQUE constraint failed")) {
                return true;
            }
        }
        return false;
    }

    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
