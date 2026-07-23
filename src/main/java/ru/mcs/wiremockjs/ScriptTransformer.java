// src/main/java/ru/mcs/wiremockjs/ScriptTransformer.java
package ru.mcs.wiremockjs;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.mcs.wiremockjs.exception.ScriptExecutionException;
import ru.mcs.wiremockjs.exception.ScriptParseException;
import ru.mcs.wiremockjs.interpreter.RequestFacade;
import ru.mcs.wiremockjs.interpreter.ScriptGuard;
import ru.mcs.wiremockjs.interpreter.WiremockJsInterpreter;
import ru.mcs.wiremockjs.model.ScriptDefinition;
import ru.mcs.wiremockjs.storage.ScriptStore;
import ru.mcs.wiremockjs.storage.ScriptStoreHolder;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

public class ScriptTransformer implements ResponseDefinitionTransformerV2 {

    private static final long EXECUTION_TIMEOUT_MS = 100;

    private final ScriptStore scriptStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScriptTransformer() {
        this(ScriptStoreHolder.getInstance());
    }

    public ScriptTransformer(ScriptStore scriptStore) {
        this.scriptStore = scriptStore;
    }

    @Override
    public String getName() {
        return "wiremock-js";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        Parameters parameters = serveEvent.getTransformerParameters();

        String scriptId = parameters.getString("scriptId");
        if (scriptId == null || scriptId.isBlank()) {
            throw new ScriptExecutionException("Параметр scriptId не указан в transformerParameters");
        }

        ScriptDefinition definition = scriptStore.findById(scriptId)
                .orElseThrow(() -> new ScriptExecutionException("Скрипт не найден: " + scriptId));

        ScriptGuard.validate(definition.getSourceCode());

        RequestFacade facade = new RequestFacade(serveEvent.getRequest());
        WiremockJsInterpreter interpreter = new WiremockJsInterpreter(facade);

        Map<String, Object> result = executeWithTimeout(interpreter, definition.getSourceCode());

        return buildResponse(serveEvent.getResponseDefinition(), result);
    }

    private Map<String, Object> executeWithTimeout(WiremockJsInterpreter interpreter, String source) {
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(
                () -> interpreter.execute(source));
        try {
            return future.get(EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ScriptExecutionException(
                    "Превышено время выполнения скрипта (" + EXECUTION_TIMEOUT_MS + " мс)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScriptParseException || cause instanceof ScriptExecutionException) {
                throw (RuntimeException) cause;
            }
            throw new ScriptExecutionException("Ошибка выполнения скрипта: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptExecutionException("Выполнение скрипта прервано");
        }
    }

    private ResponseDefinition buildResponse(ResponseDefinition original, Map<String, Object> result) {
        try {
            int status = result.containsKey("status")
                    ? ((Number) result.get("status")).intValue()
                    : original.getStatus();

            String body = objectMapper.writeValueAsString(
                    result.containsKey("body") ? result.get("body") : result);

            ResponseDefinitionBuilder builder = ResponseDefinitionBuilder.like(original)
                    .withStatus(status)
                    .withBody(body)
                    .withHeader("Content-Type", "application/json");

            Object headersObj = result.get("headers");
            if (headersObj instanceof Map) {
                Map<?, ?> headers = (Map<?, ?>) headersObj;
                for (Map.Entry<?, ?> entry : headers.entrySet()) {
                    builder.withHeader(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }

            return builder.build();
        } catch (Exception e) {
            throw new ScriptExecutionException("Ошибка формирования ответа из результата скрипта: " + e.getMessage(), e);
        }
    }
}