package com.gmscpt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Application configuration singleton.
 * Reads/writes config from ~/.gmscpt/config.json
 */
public class AppConfig {

    private static AppConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path configPath;

    // Config fields
    private String language = "zh_CN";
    private String llmProvider = "dashscope"; // dashscope or openai
    private String apiKey = "";
    private String apiBaseUrl = "";
    private String modelName = "qwen-plus";
    private String lastScript = "";

    private AppConfig() {
        Path home = Path.of(System.getProperty("user.home"));
        Path configDir = home.resolve(Constants.CONFIG_DIR_NAME);
        configPath = configDir.resolve(Constants.CONFIG_FILE_NAME);
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
            instance.load();
        }
        return instance;
    }

    public void load() {
        try {
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                AppConfig loaded = GSON.fromJson(json, AppConfig.class);
                if (loaded != null) {
                    this.language = loaded.language;
                    this.llmProvider = loaded.llmProvider;
                    this.apiKey = loaded.apiKey;
                    this.apiBaseUrl = loaded.apiBaseUrl;
                    this.modelName = loaded.modelName;
                    this.lastScript = loaded.lastScript;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load config: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // Getters and Setters
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getLastScript() { return lastScript; }
    public void setLastScript(String lastScript) { this.lastScript = lastScript; }
}
