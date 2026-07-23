package ru.mcs.wiremockjs.storage;

public class ScriptStoreHolder {
    private static final String DEFAULT_STORAGE_DIR = "wiremock/scripts";
    private static volatile ScriptStore instance;

    public static synchronized ScriptStore getInstance() {
        if (instance == null) {
            String dir = System.getProperty("wiremockjs.storage.dir", DEFAULT_STORAGE_DIR);
            instance = new ScriptStore(dir);
        }
        return instance;
    }

    private ScriptStoreHolder() {
    }
}