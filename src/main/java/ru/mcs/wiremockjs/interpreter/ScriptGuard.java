package ru.mcs.wiremockjs.interpreter;

import ru.mcs.wiremockjs.exception.ScriptParseException;
import ru.mcs.wiremockjs.exception.ScriptTooLargeException;

// Первый уровень защиты: дешёвые структурные проверки скрипта без полного
// ANTLR-парсинга. Вызывается при сохранении скрипта (POST/PUT в ScriptAdminApi)
// и при каждом выполнении скрипта в ScriptTransformer.
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

        int braceDepth = 0;
        int maxBraceDepth = 0;
        int parenDepth = 0;
        boolean inString = false;

        char[] chars = source.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (inString) {
                if (c == '\\') {
                    i++; // экранированный символ — пропускаем следующий как есть
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                    braceDepth++;
                    maxBraceDepth = Math.max(maxBraceDepth, braceDepth);
                    break;
                case '}':
                    braceDepth--;
                    if (braceDepth < 0) {
                        throw new ScriptParseException(
                                "Лишняя закрывающая скобка '}' без соответствующей открывающей");
                    }
                    break;
                case '(':
                    parenDepth++;
                    break;
                case ')':
                    parenDepth--;
                    if (parenDepth < 0) {
                        throw new ScriptParseException(
                                "Лишняя закрывающая скобка ')' без соответствующей открывающей");
                    }
                    break;
                default:
                    break;
            }
        }

        if (inString) {
            throw new ScriptParseException("Незакрытая строка — отсутствует завершающая кавычка \"");
        }
        if (braceDepth != 0) {
            throw new ScriptParseException(
                    "Несбалансированные фигурные скобки '{' '}': не хватает " + braceDepth + " закрывающих");
        }
        if (parenDepth != 0) {
            throw new ScriptParseException(
                    "Несбалансированные круглые скобки '(' ')': не хватает " + parenDepth + " закрывающих");
        }
        if (maxBraceDepth > MAX_NESTING_DEPTH) {
            throw new ScriptTooLargeException(
                    "Превышена максимальная глубина вложенности блоков: " + MAX_NESTING_DEPTH);
        }
    }
}