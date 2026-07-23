package ru.mcs.wiremockjs.interpreter;

import ru.mcs.wiremockjs.exception.ScriptTooLargeException;

// Первый уровень защиты: жёсткие лимиты до парсинга.
public class ScriptGuard {

    private static final int MAX_SCRIPT_LENGTH = 2000;
    private static final int MAX_NESTING_DEPTH = 5;

    public static void validate(String source) {
        if (source == null || source.isBlank()) {
            throw new ScriptTooLargeException("Скрипт не может быть пустым");
        }
        if (source.length() > MAX_SCRIPT_LENGTH) {
            throw new ScriptTooLargeException(
                    "Скрипт превышает максимальную длину " + MAX_SCRIPT_LENGTH + " символов");
        }
        int depth = 0;
        int maxDepth = 0;
        for (char c : source.toCharArray()) {
            if (c == '{') {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else if (c == '}') {
                depth--;
            }
        }
        if (maxDepth > MAX_NESTING_DEPTH) {
            throw new ScriptTooLargeException(
                    "Превышена максимальная глубина вложенности блоков: " + MAX_NESTING_DEPTH);
        }
    }
}