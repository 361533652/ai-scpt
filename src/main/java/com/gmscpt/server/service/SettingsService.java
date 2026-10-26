package com.gmscpt.server.service;

import com.gmscpt.server.domain.Settings;
import com.gmscpt.server.store.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 设置的读写。
 *
 * <p>刻意保持「无框架依赖」：只认 key/value 与密钥存储两个端口，
 * 这样换成配置文件还是数据库都不影响上层。旧版这里是把 JSON 直接覆盖写，
 * 杀进程正好写到一半就会把配置写坏，下次启动直接起不来。
 */
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private static final String KEY_PROVIDER = "providerId";
    private static final String KEY_OLLAMA_URL = "ollamaBaseUrl";
    private static final String KEY_OLLAMA_MODEL = "ollamaModel";
    private static final String KEY_CLOUD_URL = "cloudBaseUrl";
    private static final String KEY_CLOUD_MODEL = "cloudModel";
    private static final String KEY_EMERGENCY = "emergencyHotkey";
    private static final String KEY_PRECISE = "preciseRelativeMove";
    private static final String KEY_QUEUE = "eventQueueCapacity";
    private static final String KEY_GRACE = "stopGraceMs";
    public static final String SECRET_CLOUD_KEY = "cloudApiKey";

    private final SettingsRepository repository;
    private volatile Settings cached;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    public synchronized Settings get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    private Settings load() {
        Settings def = Settings.defaults();
        try {
            Map<String, String> m = repository.loadAll();
            return new Settings(
                    m.getOrDefault(KEY_PROVIDER, def.providerId()),
                    m.getOrDefault(KEY_OLLAMA_URL, def.ollamaBaseUrl()),
                    m.getOrDefault(KEY_OLLAMA_MODEL, def.ollamaModel()),
                    m.getOrDefault(KEY_CLOUD_URL, def.cloudBaseUrl()),
                    m.getOrDefault(KEY_CLOUD_MODEL, def.cloudModel()),
                    repository.hasSecret(SECRET_CLOUD_KEY),
                    m.getOrDefault(KEY_EMERGENCY, def.emergencyHotkey()),
                    Boolean.parseBoolean(m.getOrDefault(KEY_PRECISE, String.valueOf(def.preciseRelativeMove()))),
                    parseInt(m.get(KEY_QUEUE), def.eventQueueCapacity()),
                    parseLong(m.get(KEY_GRACE), def.stopGraceMs()));
        } catch (Throwable t) {
            // 配置坏了不能让整个程序起不来：回退默认值并记一条日志
            log.warn("读取设置失败，回退默认值: {}", t.toString());
            return def;
        }
    }

    /** 保存设置；{@code cloudApiKey} 为 null 表示不改动已存的密钥，空串表示清除。 */
    public synchronized Settings save(Settings next, String cloudApiKey) {
        repository.putAll(Map.of(
                KEY_PROVIDER, nullSafe(next.providerId(), "ollama"),
                KEY_OLLAMA_URL, nullSafe(next.ollamaBaseUrl(), "http://127.0.0.1:11434"),
                KEY_OLLAMA_MODEL, nullSafe(next.ollamaModel(), "qwen2.5-coder:7b"),
                KEY_CLOUD_URL, nullSafe(next.cloudBaseUrl(), "https://api.openai.com"),
                KEY_CLOUD_MODEL, nullSafe(next.cloudModel(), "gpt-4o-mini"),
                KEY_EMERGENCY, nullSafe(next.emergencyHotkey(), "ctrl+shift+f12"),
                KEY_PRECISE, String.valueOf(next.preciseRelativeMove()),
                KEY_QUEUE, String.valueOf(next.eventQueueCapacity()),
                KEY_GRACE, String.valueOf(next.stopGraceMs())));
        if (cloudApiKey != null) {
            if (cloudApiKey.isBlank()) {
                repository.putSecret(SECRET_CLOUD_KEY, "");
            } else {
                repository.putSecret(SECRET_CLOUD_KEY, cloudApiKey);
            }
        }
        cached = load();
        return cached;
    }

    public String cloudApiKey() {
        String v = repository.getSecret(SECRET_CLOUD_KEY);
        return v == null ? "" : v;
    }

    private static String nullSafe(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return v == null ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
