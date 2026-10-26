package com.gmscpt.server.store;

import java.util.Map;

/** 键值设置存储（数据库里的一张 setting 表）。云端 apiKey 由实现单独加密存放。 */
public interface SettingsRepository {

    Map<String, String> loadAll();

    void put(String key, String value);

    void putAll(Map<String, String> values);

    /** apiKey 专用：只写不读（接口层只回 hasApiKey）。 */
    void putSecret(String key, String value);

    boolean hasSecret(String key);

    String getSecret(String key);
}
