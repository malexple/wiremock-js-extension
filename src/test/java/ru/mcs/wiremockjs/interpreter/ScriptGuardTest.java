package ru.mcs.wiremockjs.interpreter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.mcs.wiremockjs.exception.ScriptParseException;
import ru.mcs.wiremockjs.exception.ScriptTooLargeException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptGuardTest {

    @Test
    @DisplayName("Пустой скрипт бросает ScriptTooLargeException")
    void shouldRejectEmptyScript() {
        assertThrows(ScriptTooLargeException.class, () -> ScriptGuard.validate(""));
    }

    @Test
    @DisplayName("null-скрипт бросает ScriptTooLargeException")
    void shouldRejectNullScript() {
        assertThrows(ScriptTooLargeException.class, () -> ScriptGuard.validate(null));
    }

    @Test
    @DisplayName("Скрипт, превышающий максимальную длину, бросает ScriptTooLargeException")
    void shouldRejectTooLongScript() {
        String longScript = "return { \"a\": \"" + "x".repeat(2100) + "\" };";
        assertThrows(ScriptTooLargeException.class, () -> ScriptGuard.validate(longScript));
    }

    @Test
    @DisplayName("Корректный скрипт проходит валидацию без исключений")
    void shouldAcceptValidScript() {
        String script = """
                if (query("amount") > 1000) {
                  return { "approved": false, "reason": "limit exceeded" };
                } else {
                  return { "approved": true };
                }
                """;
        assertDoesNotThrow(() -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Незакрытая фигурная скобка бросает ScriptParseException")
    void shouldRejectUnclosedBrace() {
        String script = "if (query(\"x\") > 1) { return { \"a\": true };";
        assertThrows(ScriptParseException.class, () -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Лишняя закрывающая фигурная скобка бросает ScriptParseException")
    void shouldRejectExtraClosingBrace() {
        String script = "return { \"a\": true } };";
        assertThrows(ScriptParseException.class, () -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Незакрытая круглая скобка бросает ScriptParseException")
    void shouldRejectUnclosedParen() {
        String script = "if (query(\"x\") > 1 { return { \"a\": true }; }";
        assertThrows(ScriptParseException.class, () -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Лишняя закрывающая круглая скобка бросает ScriptParseException")
    void shouldRejectExtraClosingParen() {
        String script = "if (query(\"x\")) > 1) { return { \"a\": true }; }";
        assertThrows(ScriptParseException.class, () -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Незакрытая строка бросает ScriptParseException")
    void shouldRejectUnclosedString() {
        String script = "return { \"a\": \"unterminated };";
        assertThrows(ScriptParseException.class, () -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Скобки внутри строкового литерала не влияют на баланс")
    void shouldIgnoreBracesInsideStrings() {
        String script = """
                return { "message": "text with { and } and ( and ) inside" };
                """;
        assertDoesNotThrow(() -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Экранированная кавычка внутри строки не закрывает строку раньше времени")
    void shouldHandleEscapedQuoteInsideString() {
        String script = """
                return { "message": "he said \\"hello\\"" };
                """;
        assertDoesNotThrow(() -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Превышение максимальной глубины вложенности блоков бросает ScriptTooLargeException")
    void shouldRejectExcessiveNestingDepth() {
        String script = "if (true) { if (true) { if (true) { if (true) { if (true) { if (true) { "
                + "return { \"a\": true }; } } } } } }";
        assertThrows(ScriptTooLargeException.class, () -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("Ровно максимальная глубина вложенности блоков допускается")
    void shouldAcceptExactMaxNestingDepth() {
        String script = "if (true) { if (true) { if (true) { if (true) { "
                + "return { \"a\": true }; } } } }";
        assertDoesNotThrow(() -> ScriptGuard.validate(script));
    }

    @Test
    @DisplayName("MAX_SCRIPT_LENGTH читается из system property wiremockjs.max.script.length")
    void shouldRespectConfiguredMaxScriptLength() {
        String originalValue = System.getProperty("wiremockjs.max.script.length");
        System.setProperty("wiremockjs.max.script.length", "50");
        ScriptGuard.reloadMaxScriptLength();
        try {
            String longScript = "return { \"a\": \"" + "x".repeat(60) + "\" };";
            assertThrows(ScriptTooLargeException.class, () -> ScriptGuard.validate(longScript));
        } finally {
            if (originalValue != null) {
                System.setProperty("wiremockjs.max.script.length", originalValue);
            } else {
                System.clearProperty("wiremockjs.max.script.length");
            }
            ScriptGuard.reloadMaxScriptLength();
        }
    }
}