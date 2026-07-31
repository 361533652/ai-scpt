package com.gmscpt.knowledge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and provides access to the built-in knowledge base.
 * Knowledge entries are stored as JSON in resources/knowledge/knowledge.json
 */
public class KnowledgeBase {

    private static KnowledgeBase instance;
    private final List<GameData> entries = new ArrayList<>();
    private final Gson gson = new Gson();

    private KnowledgeBase() {
        load();
    }

    public static synchronized KnowledgeBase getInstance() {
        if (instance == null) {
            instance = new KnowledgeBase();
        }
        return instance;
    }

    private void load() {
        try (InputStream is = getClass().getResourceAsStream("/knowledge/knowledge.json")) {
            if (is != null) {
                InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                Type listType = new TypeToken<List<GameData>>() {}.getType();
                List<GameData> loaded = gson.fromJson(reader, listType);
                if (loaded != null) {
                    entries.addAll(loaded);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load knowledge base: " + e.getMessage());
        }
    }

    public List<GameData> getAllEntries() {
        return new ArrayList<>(entries);
    }

    public List<GameData> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return getAllEntries();
        }
        String lower = keyword.toLowerCase();
        return entries.stream()
                .filter(e -> matchesKeyword(e, lower))
                .toList();
    }

    private boolean matchesKeyword(GameData entry, String keyword) {
        if (entry.getTitle() != null && entry.getTitle().toLowerCase().contains(keyword)) return true;
        if (entry.getGame() != null && entry.getGame().toLowerCase().contains(keyword)) return true;
        if (entry.getDescription() != null && entry.getDescription().toLowerCase().contains(keyword)) return true;
        if (entry.getTags() != null) {
            for (String tag : entry.getTags()) {
                if (tag.toLowerCase().contains(keyword)) return true;
            }
        }
        return false;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Format all knowledge entries as context text for AI requests.
     */
    public String getFormattedContent() {
        return formatEntries(entries);
    }

    /**
     * Format specific knowledge entries as context text for AI requests.
     */
    public String getFormattedContent(List<GameData> selected) {
        return formatEntries(selected);
    }

    private String formatEntries(List<GameData> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GameData entry : list) {
            sb.append("### ").append(entry.getTitle()).append("\n");
            sb.append("- Game: ").append(entry.getGame()).append("\n");
            sb.append("- Description: ").append(entry.getDescription()).append("\n");
            if (entry.getTags() != null && entry.getTags().length > 0) {
                sb.append("- Tags: ").append(String.join(", ", entry.getTags())).append("\n");
            }
            if (entry.getScript() != null && !entry.getScript().isBlank()) {
                sb.append("- Script:\n```lua\n").append(entry.getScript()).append("\n```\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
