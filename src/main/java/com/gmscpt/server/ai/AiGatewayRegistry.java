package com.gmscpt.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通道注册表：按 {@code id} 索引多个 {@link AiGateway}。
 *
 * <p>为什么不让调用方各自持有 {@code List<AiGateway>} 再自己建 map：那段代码在旧版被抄了三份
 * （健康检查、providers 列表、聊天入口），三处的「找不到通道怎么办」处理各不相同。
 * 这里收成一处，并且明确：<b>单个通道探活抛异常绝不能让整个列表失败</b>
 * ——一个云端端点没开，不该让本地 Ollama 也跟着从界面上消失。
 *
 * <p>线程安全：注册表在启动装配完成后只读，用 {@link LinkedHashMap} 保持注册顺序
 * （前端下拉框希望顺序稳定，而不是每次刷新乱跳）。
 */
public class AiGatewayRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayRegistry.class);

    private final Map<String, AiGateway> gateways;

    public AiGatewayRegistry() {
        this(List.of());
    }

    public AiGatewayRegistry(Collection<? extends AiGateway> initial) {
        Map<String, AiGateway> map = new LinkedHashMap<>();
        if (initial != null) {
            for (AiGateway gw : initial) {
                registerInto(map, gw);
            }
        }
        this.gateways = Collections.unmodifiableMap(map);
    }

    /** 注册一个通道，返回 this 以便链式装配。同 id 覆盖（后注册的赢）。 */
    public AiGatewayRegistry register(AiGateway gateway) {
        // 不可变语义下要重建一次 map；装配期调用，开销可忽略。
        Map<String, AiGateway> map = new LinkedHashMap<>(this.gateways);
        registerInto(map, gateway);
        return new AiGatewayRegistry(map.values());
    }

    /** 按 id 取通道，没有则返回 {@code null}（调用方转成 400 未知通道）。 */
    public AiGateway get(String id) {
        return id == null ? null : gateways.get(id);
    }

    /** 是否存在该通道。 */
    public boolean contains(String id) {
        return get(id) != null;
    }

    /** 全部通道 id，保持注册顺序。 */
    public List<String> ids() {
        return List.copyOf(gateways.keySet());
    }

    /** 全部通道实例，保持注册顺序。 */
    public List<AiGateway> gateways() {
        return List.copyOf(gateways.values());
    }

    /**
     * 通道元信息列表，供 {@code GET /api/ai/providers}。
     *
     * <p>逐个通道 try/catch：探活要打网络，单个通道的任何异常（连不上、超时、
     * 对端返回了奇怪的东西）都只让<b>它自己</b>变成 {@code available=false, models=[]}，
     * 不影响其他通道，也不让接口 500。
     */
    public List<AiProviderInfo> infos() {
        List<AiProviderInfo> out = new ArrayList<>(gateways.size());
        for (AiGateway gw : gateways.values()) {
            out.add(safeInfo(gw));
        }
        return out;
    }

    /** 单个通道的安全元信息；通道抛异常时降级为「不可用 + 空模型」。 */
    public AiProviderInfo info(String id) {
        AiGateway gw = get(id);
        if (gw == null) {
            return null;
        }
        return safeInfo(gw);
    }

    private static AiProviderInfo safeInfo(AiGateway gw) {
        boolean available = false;
        List<String> models = List.of();
        try {
            available = gw.available();
        } catch (Throwable t) {
            log.warn("通道 [{}] 探活异常，按不可用处理：{}", gw.id(), t.toString());
        }
        // 可用性已经确定，模型列表再单独兜一次——它打的是另一个上游调用，可能单独失败。
        try {
            models = gw.models();
            if (models == null) {
                models = List.of();
            }
        } catch (Throwable t) {
            log.warn("通道 [{}] 列模型异常，按空列表处理：{}", gw.id(), t.toString());
        }
        return new AiProviderInfo(gw.id(), available, models);
    }

    private static void registerInto(Map<String, AiGateway> map, AiGateway gw) {
        if (gw == null) {
            return;
        }
        String id = gw.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("AiGateway.id() 不能为空");
        }
        if (map.containsKey(id)) {
            log.warn("通道 id 重复：{}，后注册的覆盖前一个", id);
        }
        map.put(id, gw);
    }
}
