package ru.mcs.wiremockjs.admin;

import com.github.tomakehurst.wiremock.admin.AdminTask;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.common.url.PathParams;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.mcs.wiremockjs.model.ScriptDefinition;
import ru.mcs.wiremockjs.storage.ScriptStore;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ScriptAdminApiTest {

    private ScriptStore scriptStore;
    private CapturingRouter router;
    private ScriptAdminApi api;

    @BeforeEach
    void setUp() {
        scriptStore = Mockito.mock(ScriptStore.class);
        router = new CapturingRouter();
        api = new ScriptAdminApi(scriptStore);
        api.contributeAdminApiRoutes(router);
    }

    @Test
    @DisplayName("POST с корректным скриптом сохраняет и возвращает 201")
    void shouldSaveValidScriptOnPost() {
        ScriptDefinition input = validDefinition();
        ScriptDefinition saved = validDefinition();
        saved.setId("generated-id");

        when(scriptStore.save(any(ScriptDefinition.class))).thenReturn(saved);

        ResponseDefinition response = invokePost(input);

        assertEquals(201, response.getStatus());
    }

    @Test
    @DisplayName("POST с несбалансированными скобками возвращает 400 и не сохраняет скрипт")
    void shouldReturn400OnUnbalancedBraces() {
        ScriptDefinition input = validDefinition();
        input.setSourceCode("if (true) { return { \"a\": true };");

        ResponseDefinition response = invokePost(input);

        assertEquals(400, response.getStatus());
        assertTrue(response.getBody() != null && new String(response.getBody()).contains("error"));
        Mockito.verify(scriptStore, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("POST с незакрытой строкой возвращает 400 и не сохраняет скрипт")
    void shouldReturn400OnUnclosedString() {
        ScriptDefinition input = validDefinition();
        input.setSourceCode("return { \"a\": \"unterminated };");

        ResponseDefinition response = invokePost(input);

        assertEquals(400, response.getStatus());
        Mockito.verify(scriptStore, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("POST со скриптом, превышающим лимит длины, возвращает 400 и не сохраняет скрипт")
    void shouldReturn400OnTooLongScript() {
        ScriptDefinition input = validDefinition();
        input.setSourceCode("return { \"a\": \"" + "x".repeat(2100) + "\" };");

        ResponseDefinition response = invokePost(input);

        assertEquals(400, response.getStatus());
        Mockito.verify(scriptStore, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("PUT с корректным скриптом сохраняет и возвращает 200")
    void shouldSaveValidScriptOnPut() {
        ScriptDefinition input = validDefinition();
        ScriptDefinition saved = validDefinition();
        saved.setId("existing-id");

        when(scriptStore.save(any(ScriptDefinition.class))).thenReturn(saved);

        ResponseDefinition response = invokePut("existing-id", input);

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("PUT с несбалансированными скобками возвращает 400 и не сохраняет скрипт")
    void shouldReturn400OnPutWithUnbalancedBraces() {
        ScriptDefinition input = validDefinition();
        input.setSourceCode("if (true) { return { \"a\": true };");

        ResponseDefinition response = invokePut("existing-id", input);

        assertEquals(400, response.getStatus());
        Mockito.verify(scriptStore, Mockito.never()).save(any());
    }

    private ScriptDefinition validDefinition() {
        ScriptDefinition def = new ScriptDefinition();
        def.setName("Test script");
        def.setDescription("desc");
        def.setSourceCode("""
                if (query("amount") > 1000) {
                  return { "approved": false };
                } else {
                  return { "approved": true };
                }
                """);
        return def;
    }

    private ResponseDefinition invokePost(ScriptDefinition def) {
        AdminTask task = router.get(RequestMethod.POST, "/extensions/wiremock-js/scripts");
        ServeEvent serveEvent = mockServeEventWithBody(def);
        return task.execute(null, serveEvent, PathParams.empty());
    }

    private ResponseDefinition invokePut(String id, ScriptDefinition def) {
        AdminTask task = router.get(RequestMethod.PUT, "/extensions/wiremock-js/scripts/{id}");
        ServeEvent serveEvent = mockServeEventWithBody(def);
        return task.execute(null, serveEvent, PathParams.single("id", id));
    }

    private ServeEvent mockServeEventWithBody(ScriptDefinition def) {
        LoggedRequest request = Mockito.mock(LoggedRequest.class);
        String json = com.github.tomakehurst.wiremock.common.Json.write(def);
        when(request.getBodyAsString()).thenReturn(json);

        ServeEvent serveEvent = Mockito.mock(ServeEvent.class);
        when(serveEvent.getRequest()).thenReturn(request);
        return serveEvent;
    }

    private static class CapturingRouter implements Router {
        private final Map<String, AdminTask> routes = new HashMap<>();

        @Override
        public void add(RequestMethod method, String urlTemplate, Class<? extends AdminTask> taskClass) {
            // Не используется в ScriptAdminApi — регистрация идёт только через лямбды AdminTask.
        }

        @Override
        public void add(RequestMethod method, String urlTemplate, AdminTask task) {
            routes.put(method.getName() + " " + urlTemplate, task);
        }

        AdminTask get(RequestMethod method, String urlTemplate) {
            AdminTask task = routes.get(method.getName() + " " + urlTemplate);
            if (task == null) {
                throw new IllegalStateException("Route not registered: " + method + " " + urlTemplate);
            }
            return task;
        }
    }
}