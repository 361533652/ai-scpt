package com.gmscpt.server.ai;

import com.gmscpt.server.domain.Settings;

/**
 * 可热重配的 AI 通道。
 *
 * <p>界面改 Ollama 地址/模型或云端 baseUrl 之后要立刻生效，不该要求重启程序
 * （旧版改配置就得重启）。实现方负责按新配置重建内部客户端，并丢弃旧的。
 */
public interface ReconfigurableGateway {

    /** 应用新设置；{@code cloudApiKey} 为 null 表示沿用已存密钥。 */
    void reconfigure(Settings settings, String cloudApiKey);
}
