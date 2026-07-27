package ru.mcs.wiremockjs.admin;

import com.github.tomakehurst.wiremock.admin.AdminTask;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.url.PathParams;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import ru.mcs.wiremockjs.exception.ScriptParseException;
import ru.mcs.wiremockjs.exception.ScriptTooLargeException;
import ru.mcs.wiremockjs.interpreter.ScriptGuard;
import ru.mcs.wiremockjs.model.ScriptDefinition;
import ru.mcs.wiremockjs.model.ScriptSummary;
import ru.mcs.wiremockjs.storage.ScriptStore;
import ru.mcs.wiremockjs.storage.ScriptStoreHolder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ScriptAdminApi implements AdminApiExtension {

    private final ScriptStore scriptStore;

    public ScriptAdminApi() {
        this(ScriptStoreHolder.getInstance());
    }

    public ScriptAdminApi(ScriptStore scriptStore) {
        this.scriptStore = scriptStore;
    }

    @Override
    public String getName() {
        return "wiremock-js-admin";
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {

        router.add(RequestMethod.GET, "/extensions/wiremock-js/scripts",
                (AdminTask) (Admin admin, ServeEvent serveEvent, PathParams pathParams) -> {
                    Request request = serveEvent.getRequest();

                    QueryParameter nameParam = request.queryParameter("name");
                    String nameFilter = (nameParam != null && nameParam.isPresent())
                            ? nameParam.firstValue()
                            : null;

                    List<ScriptDefinition> found = (nameFilter == null || nameFilter.isBlank())
                            ? scriptStore.findAll()
                            : scriptStore.findByName(nameFilter);

                    List<ScriptSummary> summaries = found.stream()
                            .map(ScriptSummary::from)
                            .collect(Collectors.toList());

                    return jsonResponse(summaries, 200);
                });

        router.add(RequestMethod.GET, "/extensions/wiremock-js/scripts/{id}",
                (AdminTask) (Admin admin, ServeEvent serveEvent, PathParams pathParams) ->
                        scriptStore.findById(pathParams.get("id"))
                                .map(def -> jsonResponse(def, 200))
                                .orElseGet(() -> emptyResponse(404)));

        router.add(RequestMethod.POST, "/extensions/wiremock-js/scripts",
                (AdminTask) (Admin admin, ServeEvent serveEvent, PathParams pathParams) -> {
                    Request request = serveEvent.getRequest();
                    ScriptDefinition def = Json.read(request.getBodyAsString(), ScriptDefinition.class);

                    try {
                        ScriptGuard.validate(def.getSourceCode());
                    } catch (ScriptParseException | ScriptTooLargeException e) {
                        return errorResponse(e.getMessage(), 400);
                    }

                    ScriptDefinition saved = scriptStore.save(def);
                    return jsonResponse(saved, 201);
                });

        router.add(RequestMethod.PUT, "/extensions/wiremock-js/scripts/{id}",
                (AdminTask) (Admin admin, ServeEvent serveEvent, PathParams pathParams) -> {
                    Request request = serveEvent.getRequest();
                    ScriptDefinition def = Json.read(request.getBodyAsString(), ScriptDefinition.class);

                    try {
                        ScriptGuard.validate(def.getSourceCode());
                    } catch (ScriptParseException | ScriptTooLargeException e) {
                        return errorResponse(e.getMessage(), 400);
                    }

                    def.setId(pathParams.get("id"));
                    return jsonResponse(scriptStore.save(def), 200);
                });

        router.add(RequestMethod.DELETE, "/extensions/wiremock-js/scripts/{id}",
                (AdminTask) (Admin admin, ServeEvent serveEvent, PathParams pathParams) -> {
                    boolean deleted = scriptStore.delete(pathParams.get("id"));
                    return emptyResponse(deleted ? 204 : 404);
                });
    }

    private ResponseDefinition jsonResponse(Object body, int status) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(Json.write(body))
                .build();
    }

    private ResponseDefinition errorResponse(String message, int status) {
        Map<String, String> body = Map.of("error", message);
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(Json.write(body))
                .build();
    }

    private ResponseDefinition emptyResponse(int status) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .build();
    }
}