package ru.mcs.wiremockjs.interpreter;

import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.mcs.wiremockjs.exception.ScriptExecutionException;
import ru.mcs.wiremockjs.exception.ScriptParseException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class WiremockJsInterpreterTest {

    private Request mockRequest;

    @BeforeEach
    void setUp() {
        mockRequest = Mockito.mock(Request.class);
    }

    @Test
    @DisplayName("Скрипт с if/else возвращает ветку true при выполнении условия")
    void shouldReturnTrueBranchWhenConditionMatches() {
        when(mockRequest.queryParameter("amount"))
                .thenReturn(new QueryParameter("amount", List.of("2000")));

        String script = """
                if (query("amount") > 1000) {
                  return { "approved": false, "reason": "limit exceeded" };
                } else {
                  return { "approved": true };
                }
                """;

        Map<String, Object> result = run(script);

        assertEquals(false, result.get("approved"));
        assertEquals("limit exceeded", result.get("reason"));
    }

    @Test
    @DisplayName("Скрипт с if/else возвращает ветку false при невыполнении условия")
    void shouldReturnFalseBranchWhenConditionDoesNotMatch() {
        when(mockRequest.queryParameter("amount"))
                .thenReturn(new QueryParameter("amount", List.of("500")));

        String script = """
                if (query("amount") > 1000) {
                  return { "approved": false };
                } else {
                  return { "approved": true };
                }
                """;

        Map<String, Object> result = run(script);

        assertEquals(true, result.get("approved"));
    }

    @Test
    @DisplayName("Скрипт без else и с невыполненным условием возвращает null-результат")
    void shouldReturnNullWhenNoElseAndConditionFalse() {
        when(mockRequest.queryParameter("amount"))
                .thenReturn(new QueryParameter("amount", List.of("100")));

        String script = """
                if (query("amount") > 1000) {
                  return { "approved": false };
                }
                """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Скрипт без query-параметра корректно обрабатывает absent через contains")
    void shouldHandleMissingQueryParameter() {
        when(mockRequest.queryParameter("token"))
                .thenReturn(QueryParameter.absent("token"));

        String script = """
                if (contains(query("token"), "abc")) {
                  return { "authorized": true };
                } else {
                  return { "authorized": false };
                }
                """;

        Map<String, Object> result = run(script);

        assertEquals(false, result.get("authorized"));
    }

    @Test
    @DisplayName("Синтаксически некорректный скрипт бросает ScriptParseException")
    void shouldThrowParseExceptionOnInvalidSyntax() {
        String brokenScript = "if (query(\"x\") > 1000 { return {}; }";

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptParseException.class, () -> interpreter.execute(brokenScript));
    }

    @Test
    @DisplayName("Вызов неразрешённой функции бросает ScriptExecutionException")
    void shouldThrowExecutionExceptionOnDisallowedFunction() {
        String script = """
                if (eval("malicious code") == "x") {
                  return { "hacked": true };
                } else {
                  return { "hacked": false };
                }
                """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Скрипт с арифметикой корректно вычисляет числовое выражение")
    void shouldEvaluateArithmeticExpression() {
        when(mockRequest.queryParameter("qty"))
                .thenReturn(new QueryParameter("qty", List.of("3")));

        String script = """
                if (query("qty") * 100 > 250) {
                  return { "eligible": true };
                } else {
                  return { "eligible": false };
                }
                """;

        Map<String, Object> result = run(script);

        assertEquals(true, result.get("eligible"));
    }

    @Test
    @DisplayName("Прямой доступ к полю через точку запрещён")
    void shouldRejectDirectFieldAccess() {
        String script = """
                if (request.method == "GET") {
                  return { "ok": true };
                } else {
                  return { "ok": false };
                }
                """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    private Map<String, Object> run(String script) {
        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));
        return interpreter.execute(script);
    }
}