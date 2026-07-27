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

import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    @DisplayName("Литерал null возвращается как Java null")
    void shouldParseNullLiteral() {
        String script = """
        return { "result": null };
        """;

        Map<String, Object> result = run(script);

        assertNull(result.get("result"));
    }

    @Test
    @DisplayName("Сравнение null == null возвращает true")
    void shouldTreatNullEqualsNullAsTrue() {
        String script = """
        if (null == null) {
          return { "matched": true };
        } else {
          return { "matched": false };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(true, result.get("matched"));
    }

    @Test
    @DisplayName("jsonField() для отсутствующего пути сравнивается с null через ==")
    void shouldCompareMissingJsonFieldWithNull() {
        when(mockRequest.getBodyAsString()).thenReturn("{\"user\": {\"role\": \"admin\"}}");

        String script = """
        var city = jsonField("user.address.city");
        if (city == null) {
          return { "hasCity": false };
        } else {
          return { "hasCity": true };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(false, result.get("hasCity"));
    }

    @Test
    @DisplayName("null не равен строке null и не равен строке-значению")
    void shouldNotConfuseNullWithStringNull() {
        String script = """
        if (null == "null") {
          return { "matched": true };
        } else {
          return { "matched": false };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(false, result.get("matched"));
    }

    @Test
    @DisplayName("null в условии if трактуется как false")
    void shouldTreatNullAsFalsyInCondition() {
        String script = """
        var x = null;
        if (x) {
          return { "result": "truthy" };
        } else {
          return { "result": "falsy" };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals("falsy", result.get("result"));
    }

    @Test
    @DisplayName("Многоуровневая навигация по полям переменной-объекта")
    void shouldNavigateNestedFieldsOnVariable() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"amount\": 1500, \"details\": {\"city\": \"Moscow\"}}");

        String script = """
        var order = jsonField("$");
        return { "amount": order.amount, "city": order.details.city };
        """;

        Map<String, Object> result = run(script);

        assertEquals(1500, result.get("amount"));
        assertEquals("Moscow", result.get("city"));
    }

    @Test
    @DisplayName("Навигация по отсутствующему полю объекта возвращает null")
    void shouldReturnNullForMissingFieldOnVariable() {
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"amount\": 1500}");

        String script = """
        var order = jsonField("$");
        return { "city": order.details.city };
        """;

        Map<String, Object> result = run(script);

        assertNull(result.get("city"));
    }

    @Test
    @DisplayName("Навигация через точку по не-объектной переменной бросает ScriptExecutionException")
    void shouldThrowWhenNavigatingNonObjectVariable() {
        String script = """
        var count = 10;
        return { "result": count.amount };
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Навигация через точку по необъявленной переменной бросает ScriptExecutionException")
    void shouldThrowWhenBaseVariableNotDeclared() {
        String script = """
        return { "result": order.amount };
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Попытка обратиться к внутренним объектам WireMock через точку остаётся заблокированной")
    void shouldStillBlockAccessToRequestObject() {
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

    @Test
    @DisplayName("Полный сценарий: var + jsonField + многоуровневая навигация + null-проверка + contains")
    void shouldRunFullScenarioWithNestedFieldAccess() {
        when(mockRequest.header("Authorization"))
                .thenReturn(new com.github.tomakehurst.wiremock.http.HttpHeader(
                        "Authorization", "Bearer secret-token-123"));
        when(mockRequest.getBodyAsString())
                .thenReturn("{\"amount\": 1500}");

        String script = """
        var token = header("Authorization");
        var order = jsonField("$");
        if (order.amount != null && contains(token, "secret") && order.amount > 1000) {
          return {
            "status": 400,
            "body": {
              "error": "Limit exceeded",
              "details": {
                "maxLimit": 1000,
                "currentAmount": order.amount
              }
            }
          };
        }
        return { "status": 200, "approved": true };
        """;

        Map<String, Object> result = run(script);

        assertEquals(400L, result.get("status"));
    }

    @Test
    @DisplayName("contains() с null в качестве искомой строки безопасно возвращает false")
    void shouldReturnFalseWhenContainsNeedleIsNull() {
        String script = """
        var x = null;
        if (contains("some text", x)) {
          return { "matched": true };
        } else {
          return { "matched": false };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(false, result.get("matched"));
    }

    @Test
    @DisplayName("contains() с null в качестве исходной строки безопасно возвращает false")
    void shouldReturnFalseWhenContainsHaystackIsNull() {
        when(mockRequest.queryParameter("token"))
                .thenReturn(QueryParameter.absent("token"));

        String script = """
        if (contains(query("token"), "secret")) {
          return { "matched": true };
        } else {
          return { "matched": false };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(false, result.get("matched"));
    }

    @Test
    @DisplayName("Сравнение null с числом через > бросает ScriptExecutionException")
    void shouldThrowWhenComparingNullWithNumber() {
        when(mockRequest.queryParameter("amount"))
                .thenReturn(QueryParameter.absent("amount"));

        String script = """
        if (query("amount") > 1000) {
          return { "approved": false };
        } else {
          return { "approved": true };
        }
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Арифметика с null-переменной бросает ScriptExecutionException")
    void shouldThrowWhenArithmeticWithNullVariable() {
        String script = """
        var x = null;
        return { "result": x + 5 };
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        assertThrows(ScriptExecutionException.class, () -> interpreter.execute(script));
    }

    @Test
    @DisplayName("Нечисловая строка в арифметике бросает ScriptExecutionException с понятным сообщением")
    void shouldThrowWithClearMessageForNonNumericString() {
        String script = """
        var x = "abc";
        return { "result": x + 5 };
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        ScriptExecutionException ex = assertThrows(ScriptExecutionException.class,
                () -> interpreter.execute(script));
        assertTrue(ex.getMessage().contains("abc"));
    }

    @Test
    @DisplayName("Рекомендуемый паттерн: явная null-проверка перед арифметикой предотвращает исключение")
    void shouldAvoidExceptionWithExplicitNullCheck() {
        when(mockRequest.queryParameter("amount"))
                .thenReturn(QueryParameter.absent("amount"));

        String script = """
        var amount = query("amount");
        if (amount != null && amount > 1000) {
          return { "approved": false };
        } else {
          return { "approved": true };
        }
        """;

        Map<String, Object> result = run(script);

        assertEquals(true, result.get("approved"));
    }

    @Test
    @DisplayName("random() без seed возвращает значение в диапазоне [0, 1)")
    void shouldReturnRandomValueInRangeWithoutSeed() {
        String script = """
        return { "result": random() };
        """;

        Map<String, Object> result = run(script);

        double value = ((Number) result.get("result")).doubleValue();
        assertTrue(value >= 0.0 && value < 1.0);
    }

    @Test
    @DisplayName("random() с одинаковым seed даёт одинаковую последовательность")
    void shouldBeDeterministicWithSameSeed() {
        String script = """
        return { "a": random(), "b": random(), "c": random() };
        """;

        WiremockJsInterpreter interpreter1 = new WiremockJsInterpreter(new RequestFacade(mockRequest));
        WiremockJsInterpreter interpreter2 = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        Map<String, Object> result1 = interpreter1.execute(script, 42);
        Map<String, Object> result2 = interpreter2.execute(script, 42);

        assertEquals(result1.get("a"), result2.get("a"));
        assertEquals(result1.get("b"), result2.get("b"));
        assertEquals(result1.get("c"), result2.get("c"));
    }

    @Test
    @DisplayName("random() с разными seed даёт разные последовательности")
    void shouldDifferWithDifferentSeeds() {
        String script = """
        return { "a": random() };
        """;

        WiremockJsInterpreter interpreter1 = new WiremockJsInterpreter(new RequestFacade(mockRequest));
        WiremockJsInterpreter interpreter2 = new WiremockJsInterpreter(new RequestFacade(mockRequest));

        Map<String, Object> result1 = interpreter1.execute(script, 1);
        Map<String, Object> result2 = interpreter2.execute(script, 2);

        assertNotEquals(result1.get("a"), result2.get("a"));
    }

    @Test
    @DisplayName("Chaos engineering сценарий: random() с seed управляет вероятностью ошибки")
    void shouldSupportChaosEngineeringScenarioWithSeed() {
        String script = """
        if (random() < 0.5) {
          return { "status": 500, "body": { "error": "internal error" } };
        } else {
          return { "status": 200, "body": { "ok": true } };
        }
        """;

        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(new RequestFacade(mockRequest));
        Map<String, Object> result = interpreter.execute(script, 7);

        assertTrue(result.get("status").equals(500L) || result.get("status").equals(200L));
    }

    @Test
    @DisplayName("Старый метод execute(source) без seed продолжает работать (обратная совместимость)")
    void shouldKeepBackwardCompatibilityForExecuteWithoutSeed() {
        String script = """
        return { "status": 200 };
        """;

        Map<String, Object> result = run(script);

        assertEquals(200L, result.get("status"));
    }
}