package com.gmscpt.server.domain;

/**
 * 运行期可改的设置。
 *
 * <p>注意这里**没有** apiKey 字段：密钥只进不出，接口只回 {@code hasCloudApiKey}，
 * 写入走单独的 {@code PUT /api/settings} 字段。旧版把 key 明文写在 config.json 里，
 * 而且 GET 接口会把它回给前端。
 */
public record Settings(
        String providerId,
        String ollamaBaseUrl,
        String ollamaModel,
        String cloudBaseUrl,
        String cloudModel,
        boolean hasCloudApiKey,
        String emergencyHotkey,
        boolean preciseRelativeMove,
        int eventQueueCapacity,
        long stopGraceMs
) {

    public static Settings defaults() {
        return new Settings(
                "ollama",
                "http://127.0.0.1:11434",
                "qwen2.5-coder:7b",
                "https://api.openai.com",
                "gpt-4o-mini",
                false,
                "ctrl+shift+f12",
                false,
                4096,
                3000);
    }
}
