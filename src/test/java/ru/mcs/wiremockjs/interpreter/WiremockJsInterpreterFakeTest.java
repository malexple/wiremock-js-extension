package ru.mcs.wiremockjs.interpreter;

import com.github.tomakehurst.wiremock.http.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WiremockJsInterpreterFakeTest {

    private WiremockJsInterpreter interpreterWithEmptyBody() {
        Request request = Mockito.mock(Request.class);
        Mockito.when(request.getBodyAsString()).thenReturn("{}");
        return new WiremockJsInterpreter(new RequestFacade(request));
    }

    @Test
    void shouldGenerateFakeNameFromExpression() {
        WiremockJsInterpreter interpreter = interpreterWithEmptyBody();

        String script = """
            var name = fake("#{Name.first_name} #{Name.last_name}");
            return { "name": name };
            """;

        Map<String, Object> result = interpreter.execute(script);
        String name = (String) result.get("name");

        assertNotNull(name);
        assertTrue(name.split(" ").length >= 2);
    }

    @Test
    void shouldProduceSameFakeNameForSameSeed() {
        WiremockJsInterpreter interpreter1 = interpreterWithEmptyBody();
        WiremockJsInterpreter interpreter2 = interpreterWithEmptyBody();

        String script = """
            var name = fake("#{Name.first_name} #{Name.last_name}");
            return { "name": name };
            """;

        Map<String, Object> result1 = interpreter1.execute(script, 42);
        Map<String, Object> result2 = interpreter2.execute(script, 42);

        assertEquals(result1.get("name"), result2.get("name"));
    }

    @Test
    void shouldProduceDifferentFakeNameForDifferentSeed() {
        WiremockJsInterpreter interpreter1 = interpreterWithEmptyBody();
        WiremockJsInterpreter interpreter2 = interpreterWithEmptyBody();

        String script = """
            var name = fake("#{Name.first_name} #{Name.last_name}");
            return { "name": name };
            """;

        Map<String, Object> result1 = interpreter1.execute(script, 1);
        Map<String, Object> result2 = interpreter2.execute(script, 2);

        assertNotEquals(result1.get("name"), result2.get("name"));
    }

    @Test
    void shouldKeepRandomAndFakeSynchronizedWithSameSeed() {
        WiremockJsInterpreter interpreter1 = interpreterWithEmptyBody();
        WiremockJsInterpreter interpreter2 = interpreterWithEmptyBody();

        String script = """
            var name = fake("#{Name.first_name}");
            var id = uuid();
            var n = randomInt(1, 1000);
            return { "name": name, "id": id, "n": n };
            """;

        Map<String, Object> result1 = interpreter1.execute(script, 777);
        Map<String, Object> result2 = interpreter2.execute(script, 777);

        assertEquals(result1, result2);
    }

    @Test
    void shouldThrowOnInvalidFakeExpression() {
        WiremockJsInterpreter interpreter = interpreterWithEmptyBody();

        String script = """
            var value = fake("#{NonExistingProvider.method}");
            return { "value": value };
            """;

        assertThrows(ru.mcs.wiremockjs.exception.ScriptExecutionException.class,
                () -> interpreter.execute(script));
    }
}