package ru.mcs.wiremockjs.interpreter;

import com.github.tomakehurst.wiremock.http.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WiremockJsInterpreterForEachTest {

    private WiremockJsInterpreter interpreterFor(String json) {
        Request request = Mockito.mock(Request.class);
        Mockito.when(request.getBodyAsString()).thenReturn(json);
        return new WiremockJsInterpreter(new RequestFacade(request));
    }

    @Test
    void shouldReturnInsideForEachAndStopIteration() {
        String body = "{\"items\":[{\"status\":\"ok\"},{\"status\":\"failed\"},{\"status\":\"ok\"}]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var items = jsonField("$.items");
            for (var item of items) {
                if (item.status == "failed") {
                    return { "status": 400, "error": "found failed item" };
                }
            }
            return { "status": 200, "ok": true };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(400.0, ((Number) result.get("status")).doubleValue());
    }

    @Test
    void shouldReturnDefaultResponseWhenNoMatchFound() {
        String body = "{\"items\":[{\"status\":\"ok\"},{\"status\":\"ok\"}]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var items = jsonField("$.items");
            for (var item of items) {
                if (item.status == "failed") {
                    return { "status": 400, "error": "found failed item" };
                }
            }
            return { "status": 200, "ok": true };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(200.0, ((Number) result.get("status")).doubleValue());
    }

    @Test
    void shouldNotLeakLoopVariableAfterLoop() {
        String body = "{\"items\":[1,2,3]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var items = jsonField("$.items");
            for (var item of items) {
                var x = item;
            }
            return { "x": item };
            """;

        assertThrows(ru.mcs.wiremockjs.exception.ScriptExecutionException.class,
                () -> interpreter.execute(script));
    }

    @Test
    void shouldRestorePreviousValueOfShadowedVariableAfterLoop() {
        String body = "{\"items\":[100,200]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var item = "outer";
            var items = jsonField("$.items");
            for (var item of items) {
                var ignored = item;
            }
            return { "item": item };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals("outer", result.get("item"));
    }

    @Test
    void shouldRestoreNullValueOfShadowedVariableAfterLoop() {
        String body = "{\"items\":[1,2]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var item = null;
            var items = jsonField("$.items");
            for (var item of items) {
                var ignored = item;
            }
            return { "wasNull": item == null };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(true, result.get("wasNull"));
    }

    @Test
    void shouldRejectAssignmentWithoutVarKeyword() {
        String body = "{\"items\":[]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
        var items = jsonField("$.items");
        found = true;
        return { "status": 200 };
        """;

        assertThrows(ru.mcs.wiremockjs.exception.ScriptParseException.class,
                () -> interpreter.execute(script));
    }

    @Test
    void shouldCombineForEachSearchWithSumAggregate() {
        String body = "{\"items\":[{\"price\":10,\"vip\":false},{\"price\":20,\"vip\":true},{\"price\":30,\"vip\":false}]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var items = jsonField("$.items");
            var prices = jsonField("$.items[*].price");
            var total = sum(prices);
            for (var item of items) {
                if (item.vip == true) {
                    return { "status": 200, "total": total, "vipFound": true };
                }
            }
            return { "status": 200, "total": total, "vipFound": false };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(60.0, ((Number) result.get("total")).doubleValue());
        assertEquals(true, result.get("vipFound"));
    }
}