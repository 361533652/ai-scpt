package com.gmscpt.server.ai;

import java.util.List;

/**
 * 通道元信息。
 *
 * @param id       通道 id
 * @param available 是否可用
 * @param models   可用模型列表（探活失败时为空列表）
 */
public record AiProviderInfo(String id, boolean available, List<String> models) {
}
