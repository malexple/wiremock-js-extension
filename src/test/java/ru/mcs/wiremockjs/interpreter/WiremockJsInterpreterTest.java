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

    @Test
    @DisplayName("jsonField() читает вложенное поле из JSON-тела запроса")
    void shouldReadNestedJsonField() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"user\": {\"role\": \"admin\"}}");

        String script = """
            if (jsonField("user.role") == "admin") {
              return { "status": 200, "body": { "access": "granted" } };
            } else {
              return { "status": 403, "body": { "access": "denied" } };
            }
            """;

        Map<String, Object> result = run(script);

        assertEquals(200L, result.get("status"));
    }

    @Test
    @DisplayName("jsonField() возвращает null для несуществующего пути")
    void shouldReturnNullForMissingJsonPath() {
        when(mockRequest.getBodyAsString()).thenReturn("{\"user\": {\"role\": \"admin\"}}");

        String script = """
            return { "city": jsonField("user.address.city") };
            """;

        Map<String, Object> result = run(script);

        assertEquals(null, result.get("city"));
    }

    @Test
    @DisplayName("jsonField() безопасно обрабатывает невалидный JSON в теле")
    void shouldReturnNullForInvalidJsonBody() {
        when(mockRequest.getBodyAsString()).thenReturn("not a json at all");

        String script = """
            return { "field": jsonField("anything") };
            """;

        Map<String, Object> result = run(script);

        assertEquals(null, result.get("field"));
    }

    @Test
    @DisplayName("jsonField() читает элемент массива по числовому индексу")
    void shouldReadArrayElementByIndex() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"items\": [{\"id\": 1}, {\"id\": 2}]}");

        String script = """
        if (jsonField("items[1].id") == 2) {
          return { "matched": true };
        } else {
          return { "matched": false };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(true, result.get("matched"));
    }

    @Test
    @DisplayName("jsonField() с фильтром возвращает список подходящих значений")
    void shouldReturnListForFilterExpression() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"items\": [{\"id\": 1, \"status\": \"active\"}, {\"id\": 2, \"status\": \"inactive\"}]}");

        String script = """
        return { "result": jsonField("items[?(@.status=='active')].id") };
        """;

        Map<String, Object> result = run(script);

        assertEquals(List.of(1), result.get("result"));
    }

    @Test
    @DisplayName("jsonField() с фильтром без совпадений возвращает пустой список")
    void shouldReturnEmptyListWhenFilterMatchesNothing() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"items\": [{\"id\": 1, \"status\": \"inactive\"}]}");

        String script = """
        return { "result": jsonField("items[?(@.status=='active')].id") };
        """;

        Map<String, Object> result = run(script);

        assertEquals(List.of(), result.get("result"));
    }

    @Test
    @DisplayName("jsonField() с wildcard возвращает список всех значений по ключу")
    void shouldReturnListForWildcardExpression() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"items\": [{\"id\": 1}, {\"id\": 2}, {\"id\": 3}]}");

        String script = """
        return { "result": jsonField("items[*].id") };
        """;

        Map<String, Object> result = run(script);

        assertEquals(List.of(1, 2, 3), result.get("result"));
    }

    @Test
    @DisplayName("jsonField() с wildcard на пустом массиве возвращает пустой список")
    void shouldReturnEmptyListForWildcardOnEmptyArray() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"items\": []}");

        String script = """
        return { "result": jsonField("items[*].id") };
        """;

        Map<String, Object> result = run(script);

        assertEquals(List.of(), result.get("result"));
    }

    @Test
    @DisplayName("jsonField() с фильтром по числовому условию возвращает подходящие элементы")
    void shouldReturnListForNumericFilterExpression() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"items\": [{\"id\": 1, \"amount\": 500}, {\"id\": 2, \"amount\": 1500}]}");

        String script = """
        return { "result": jsonField("items[?(@.amount>1000)].id") };
        """;

        Map<String, Object> result = run(script);

        assertEquals(List.of(2), result.get("result"));
    }

    @Test
    @DisplayName("var объявляет переменную и она доступна в return")
    void shouldDeclareAndUseVariable() {
        String script = """
        var status = "ok";
        return { "result": status };
        """;

        Map<String, Object> result = run(script);

        assertEquals("ok", result.get("result"));
    }

    @Test
    @DisplayName("var с результатом jsonField используется в условии")
    void shouldUseVariableFromJsonFieldInCondition() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"user\": {\"role\": \"admin\"}}");

        String script = """
        var role = jsonField("user.role");
        if (role == "admin") {
          return { "access": "granted" };
        } else {
          return { "access": "denied" };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals("granted", result.get("access"));
    }

    @Test
    @DisplayName("Использование необъявленной переменной бросает ScriptExecutionException")
    void shouldThrowWhenVariableNotDeclared() {
        String script = """
        return { "result": undeclaredVar };
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Повторное объявление переменной с тем же именем перезаписывает значение")
    void shouldAllowRedeclaringVariable() {
        String script = """
        var x = "first";
        var x = "second";
        return { "result": x };
        """;

        Map<String, Object> result = run(script);

        assertEquals("second", result.get("result"));
    }

    @Test
    @DisplayName("Переменная, объявленная до if, доступна внутри веток then и else")
    void shouldShareVariableAcrossIfElseBranches() {
        when(mockRequest.queryParameter("amount"))
                .thenReturn(new QueryParameter("amount", List.of("2000")));

        String script = """
        var label = "checked";
        if (query("amount") > 1000) {
          return { "result": label, "approved": false };
        } else {
          return { "result": label, "approved": true };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals("checked", result.get("result"));
        assertEquals(false, result.get("approved"));
    }

    @Test
    @DisplayName("Числовая переменная участвует в арифметике")
    void shouldUseNumericVariableInArithmetic() {
        String script = """
        var base = 100;
        return { "total": base * 2 };
        """;

        Map<String, Object> result = run(script);

        assertEquals(200.0, result.get("total"));
    }
}