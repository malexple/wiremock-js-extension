package ru.mcs.wiremockjs.interpreter;

import java.util.HashMap;
import java.util.Map;

// То, что скрипт возвращает через return { ... } — сырой JSON-объект как Map.
public class ScriptResult {
    private final Map<String, Object> values = new HashMap<>();

    public void put(String key, Object value) {
        values.put(key, value);
    }

    public Map<String, Object> asMap() {
        return values;
    }
}