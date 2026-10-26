package com.gmscpt.server.service;

import com.gmscpt.server.domain.Settings;

/**
 * 设置变更的落地钩子。
 *
 * <p>为什么单独抽出来：设置里既有「AI 通道地址」又有「急停热键」，
 * 前者要重建 Spring AI 的模型客户端、后者要更新钩子侧的热键表。
 * API 层不该知道这些细节，只负责调用本接口。
 */
public interface SettingsApplier {

    void apply(Settings settings, String cloudApiKeyOrNull);
}
