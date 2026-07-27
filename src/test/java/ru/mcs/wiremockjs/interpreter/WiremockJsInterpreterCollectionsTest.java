package ru.mcs.wiremockjs.interpreter;

import com.github.tomakehurst.wiremock.http.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WiremockJsInterpreterCollectionsTest {

    private Request mockRequestWithBody(String json) {
        Request request = Mockito.mock(Request.class);
        Mockito.when(request.getBodyAsString()).thenReturn(json);
        return request;
    }

    private WiremockJsInterpreter interpreterFor(String json) {
        RequestFacade facade = new RequestFacade(mockRequestWithBody(json));
        return new WiremockJsInterpreter(facade);
    }

    @Test
    void shouldSumArrayOfPrices() {
        String body = "{\"orders\":[{\"price\":100},{\"price\":250},{\"price\":50}]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var prices = jsonField("$.orders[*].price");
            var total = sum(prices);
            return { "total": total };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(400.0, ((Number) result.get("total")).doubleValue());
    }

    @Test
    void shouldCountElements() {
        String body = "{\"items\":[1,2,3,4,5]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var items = jsonField("$.items");
            var total = count(items);
            return { "count": total };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(5.0, ((Number) result.get("count")).doubleValue());
    }

    @Test
    void shouldCalculateAverage() {
        String body = "{\"scores\":[10,20,30]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var scores = jsonField("$.scores");
            var average = avg(scores);
            return { "avg": average };
            """;

        Map<String, Object> result = interpreter.execute(script);
        assertEquals(20.0, ((Number) result.get("avg")).doubleValue());
    }

    @Test
    void shouldThrowOnAvgOfEmptyArray() {
        String body = "{\"scores\":[]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var scores = jsonField("$.scores");
            var average = avg(scores);
            return { "avg": average };
            """;

        assertThrows(ru.mcs.wiremockjs.exception.ScriptExecutionException.class,
                () -> interpreter.execute(script));
    }

    @Test
    void shouldThrowOnSumOfNonNumericArray() {
        String body = "{\"names\":[\"a\",\"b\"]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var names = jsonField("$.names");
            var total = sum(names);
            return { "total": total };
            """;

        assertThrows(ru.mcs.wiremockjs.exception.ScriptExecutionException.class,
                () -> interpreter.execute(script));
    }

    @Test
    void shouldRenameKeysWithMapKeys() {
        String body = "{\"old_products\":[{\"title\":\"Phone\",\"cost\":999},{\"title\":\"Case\",\"cost\":19}]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var products = jsonField("$.old_products");
            var transformed = mapKeys(products, { "title": "name", "cost": "price" });
            return { "products": transformed };
            """;

        Map<String, Object> result = interpreter.execute(script);
        java.util.List<?> products = (java.util.List<?>) result.get("products");
        Map<?, ?> first = (Map<?, ?>) products.get(0);

        assertEquals("Phone", first.get("name"));
        assertEquals(999, ((Number) first.get("price")).intValue());
        assertNull(first.get("title"));
        assertNull(first.get("cost"));
    }

    @Test
    void shouldThrowOnMapKeysWithNonObjectElement() {
        String body = "{\"items\":[1,2,3]}";
        WiremockJsInterpreter interpreter = interpreterFor(body);

        String script = """
            var items = jsonField("$.items");
            var transformed = mapKeys(items, { "a": "b" });
            return { "transformed": transformed };
            """;

        assertThrows(ru.mcs.wiremockjs.exception.ScriptExecutionException.class,
                () -> interpreter.execute(script));
    }
}