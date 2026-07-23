package ru.mcs.wiremockjs.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.mcs.wiremockjs.model.ScriptDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class ScriptStore {

    private final Path storageDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScriptStore(String storageDirPath) {
        this.storageDir = Paths.get(storageDirPath);
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось создать директорию хранилища скриптов: " + storageDirPath, e);
        }
    }

    public List<ScriptDefinition> findAll() {
        List<ScriptDefinition> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "*.json")) {
            for (Path path : stream) {
                readFile(path).ifPresent(result::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка чтения хранилища скриптов", e);
        }
        return result;
    }

    public Optional<ScriptDefinition> findById(String id) {
        Path path = pathFor(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return readFile(path);
    }

    public List<ScriptDefinition> findByName(String namePart) {
        String needle = namePart == null ? "" : namePart.toLowerCase();
        return findAll().stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase().contains(needle))
                .collect(Collectors.toList());
    }

    public ScriptDefinition save(ScriptDefinition def) {
        long now = System.currentTimeMillis();
        if (def.getId() == null || def.getId().isBlank()) {
            def.setId(UUID.randomUUID().toString());
            def.setCreatedAt(now);
        } else {
            findById(def.getId()).ifPresent(existing -> def.setCreatedAt(existing.getCreatedAt()));
        }
        def.setUpdatedAt(now);
        writeFile(def);
        return def;
    }

    public boolean delete(String id) {
        try {
            return Files.deleteIfExists(pathFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка удаления скрипта: " + id, e);
        }
    }

    private Path pathFor(String id) {
        return storageDir.resolve(id + ".json");
    }

    private Optional<ScriptDefinition> readFile(Path path) {
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), ScriptDefinition.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void writeFile(ScriptDefinition def) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(pathFor(def.getId()).toFile(), def);
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка записи скрипта: " + def.getId(), e);
        }
    }
}