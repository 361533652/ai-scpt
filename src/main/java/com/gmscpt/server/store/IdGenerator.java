package com.gmscpt.server.store;

import java.security.SecureRandom;

/**
 * 内部主键生成器：ULID 风格 = 48 位毫秒时间戳 + 80 位随机，Crockford Base32 编码共 26 字符。
 *
 * <p>为什么不用数据库自增：
 * <ul>
 *   <li>脚本 id 要当文件名（{@code dataDir/scripts/<id>.lua}），必须是文件系统安全的字符集；</li>
 *   <li>run id 要跨进程、跨重启唯一（它还兼作输入注入的回声抑制标记）；</li>
 *   <li>ULID 按字典序 == 按时间序，列表分页、日志排序都能直接用 id 当游标；</li>
 *   <li>不依赖任何第三方库（不引 ULID 库，也不需要 sqlite 的自增回读）。</li>
 * </ul>
 *
 * <p>单调性保证：同一毫秒内随机段自增，因此 {@code next()} 的返回值严格递增；
 * 系统时钟回拨时也不倒退、不重复（沿用上一个时间戳继续自增随机段）。
 */
public final class IdGenerator {

    /** Crockford Base32：去掉了容易混淆的 I/L/O/U。 */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final int RANDOM_BYTES = 10; // 80 bit
    private static final int ID_LENGTH = 26;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] LAST_RANDOM = new byte[RANDOM_BYTES];

    private static long lastTimestamp = -1L;

    private IdGenerator() {
    }

    /** 生成 26 字符 id。 */
    public static synchronized String next() {
        long now = System.currentTimeMillis();
        if (now > lastTimestamp) {
            lastTimestamp = now;
            RANDOM.nextBytes(LAST_RANDOM);
        } else {
            increment(LAST_RANDOM);
        }

        char[] out = new char[ID_LENGTH];
        long ts = lastTimestamp;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (ts & 31L)];
            ts >>>= 5;
        }
        for (int i = 15; i >= 0; i--) {
            out[10 + i] = ALPHABET[extract5Bits(LAST_RANDOM, i * 5)];
        }
        return new String(out);
    }

    /** 带前缀的 id，例如 {@code next("run") -> "run_01JC…"}；前缀只为人眼辨识，不参与排序。 */
    public static String next(String prefix) {
        String id = next();
        return (prefix == null || prefix.isBlank()) ? id : prefix + "_" + id;
    }

    /** 80 位大端自增；耗尽（同一毫秒 2^80 次，实际不可能）则重置随机段并推进时间戳。 */
    private static void increment(byte[] random) {
        for (int i = random.length - 1; i >= 0; i--) {
            if (++random[i] != 0) {
                return;
            }
        }
        RANDOM.nextBytes(random);
        lastTimestamp++;
    }

    /** 从字节数组里按 MSB-first 取 5 位。 */
    private static int extract5Bits(byte[] data, int bitOffset) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int bit = bitOffset + i;
            int b = data[bit >> 3] & 0xFF;
            value = (value << 1) | ((b >> (7 - (bit & 7))) & 1);
        }
        return value;
    }
}
