package com.gmscpt.server.ai;

import java.time.Duration;

/**
 * 一次上游 AI 请求的三段超时上限：连接 / 首字节 / 块间静默。
 *
 * <p>为什么要把它们显式建模：旧版的客户端用的是 {@code readTimeout(0)}，语义就是「永远等下去」，
 * 上游一挂起聊天框就永久锁死（ADR-0001 的 F4/H4）。这里三段都必须是有界的正数，
 * 构造时直接拒绝 null/0/负数——宁可启动就炸，也不要运行期静默地无限等待。
 *
 * <p>为什么分首字节与块间静默两段：本地 8B 模型冷启动（把权重读进显存）可能要几十秒，
 * 那段时间一个字都不会吐；而一旦开始吐字，超过两分钟没有任何新块基本可以断定上游卡死。
 * 用一个总超时把两者混在一起，要么误杀冷启动，要么永远等不到卡死的连接被终结。
 *
 * @param connect   建立 TCP/TLS 连接的上限（JDK HttpClient 层）
 * @param firstByte 请求发出到收到第一块数据的上限（含模型冷加载）
 * @param idle      两块数据之间的静默上限（首块之后，每收到一块重新计时）
 */
public record AiTimeouts(Duration connect, Duration firstByte, Duration idle) {

    public AiTimeouts {
        connect = requirePositive(connect, "connect");
        firstByte = requirePositive(firstByte, "firstByte");
        idle = requirePositive(idle, "idle");
    }

    public static AiTimeouts defaults() {
        // 10s 建连：本机 loopback 顶多毫秒级，云端 10s 也已远超正常值。
        // 60s 首字节：给 qwen3:8b 这种冷加载留足余量。
        // 120s 块间静默：思考链长的模型偶尔会憋很久才吐下一块，但不会憋到两分钟。
        return new AiTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(120));
    }

    /** 异常信息里用的一句话描述（前端会看到）。 */
    public String describe() {
        return "首字节上限 " + firstByte.toSeconds() + "s / 块间静默上限 " + idle.toSeconds() + "s";
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " 超时必须是正数：0 或负数等于「永久等待」，正是旧版 readTimeout(0) 的病根");
        }
        return value;
    }
}
