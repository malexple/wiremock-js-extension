package ru.mcs.wiremockjs.model;

// Облегчённая версия для списочных ответов API (без sourceCode)
public class ScriptSummary {
    private String id;
    private String name;
    private String description;
    private long createdAt;
    private long updatedAt;

    public static ScriptSummary from(ScriptDefinition def) {
        ScriptSummary s = new ScriptSummary();
        s.id = def.getId();
        s.name = def.getName();
        s.description = def.getDescription();
        s.createdAt = def.getCreatedAt();
        s.updatedAt = def.getUpdatedAt();
        return s;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}