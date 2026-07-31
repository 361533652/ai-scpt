package com.gmscpt.knowledge;

/**
 * Data model for a knowledge base entry (game macro example/template).
 */
public class GameData {

    private String id;
    private String game;
    private String title;
    private String description;
    private String script;
    private String[] tags;

    public GameData() {}

    public GameData(String id, String game, String title, String description, String script, String[] tags) {
        this.id = id;
        this.game = game;
        this.title = title;
        this.description = description;
        this.script = script;
        this.tags = tags;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGame() { return game; }
    public void setGame(String game) { this.game = game; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }

    public String[] getTags() { return tags; }
    public void setTags(String[] tags) { this.tags = tags; }

    @Override
    public String toString() {
        return String.format("[%s] %s - %s", game, title, description);
    }
}
