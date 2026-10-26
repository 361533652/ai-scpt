package com.gmscpt.server.kernel.input;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 自注入回声抑制（修 ADR 里的 H7）。
 *
 * <p>旧版用「100ms 时间窗 + 两套按钮语义」判断，结果集合永远不相等，
 * 自己注入的鼠标点击会被当成真实事件回灌给脚本，形成自激；
 * 同时真实快速点击会被时间窗误吞。
 *
 * <p>这里改成**精确配对登记**：每次注入前登记一个待认领的令牌，
 * 钩子线程收到事件时先尝试认领；认领成功即判定为「自己造的」，直接丢弃。
 * 令牌带过期时间（默认 2 秒），避免注入失败时留下幽灵令牌。
 */
public final class EchoSuppressor {

    private static final long TTL_MILLIS = 2000L;

    private final Map<String, ConcurrentLinkedDeque<Long>> pending = new ConcurrentHashMap<>();

    public static String keyToken(String keyName, boolean down) {
        return (down ? "kd:" : "ku:") + keyName;
    }

    public static String buttonToken(int canonicalButton, boolean down) {
        return (down ? "md:" : "mu:") + canonicalButton;
    }

    /** 注入前登记：我即将造出一个这样的事件。 */
    public void expect(String token) {
        pending.computeIfAbsent(token, k -> new ConcurrentLinkedDeque<>())
                .addLast(System.currentTimeMillis() + TTL_MILLIS);
    }

    /** 钩子收到事件时认领；true = 这是我自己造的，不该派发给脚本。 */
    public boolean claim(String token) {
        ConcurrentLinkedDeque<Long> deque = pending.get(token);
        if (deque == null) return false;
        long now = System.currentTimeMillis();
        while (true) {
            Long expiry = deque.pollFirst();
            if (expiry == null) {
                pending.remove(token, deque);
                return false;
            }
            if (expiry >= now) {
                return true;
            }
            // 过期的令牌：丢弃后继续看下一个
        }
    }

    public void clear() {
        pending.clear();
    }
}
