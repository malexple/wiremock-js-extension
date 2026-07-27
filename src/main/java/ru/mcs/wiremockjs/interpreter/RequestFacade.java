package ru.mcs.wiremockjs.interpreter;

import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import ru.mcs.wiremockjs.exception.ScriptExecutionException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Единственная точка доступа скрипта к реальному Request WireMock.
// Никаких прямых ссылок на внутренние объекты WireMock не отдаётся наружу.
public class RequestFacade {

    private static final Map<String, JsonPath> PATH_CACHE = new ConcurrentHashMap<>();

    private final Request request;
    private DocumentContext documentContext;
    private boolean documentContextInitialized = false;

    public RequestFacade(Request request) {
        this.request = request;
    }

    public Object jsonField(String path) {
        DocumentContext context = getOrParseDocumentContext();
        if (context == null) {
            return null;
        }

        JsonPath compiledPath = PATH_CACHE.computeIfAbsent(path, JsonPath::compile);

        try {
            return context.read(compiledPath);
        } catch (PathNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new ScriptExecutionException("Ошибка при чтении JSONPath \"" + path + "\": " + e.getMessage());
        }
    }

    private DocumentContext getOrParseDocumentContext() {
        if (!documentContextInitialized) {
            documentContextInitialized = true;
            try {
                documentContext = JsonPath.parse(request.getBodyAsString());
            } catch (Exception e) {
                documentContext = null;
            }
        }
        return documentContext;
    }

    public String query(String name) {
        QueryParameter param = request.queryParameter(name);
        return (param != null && param.isPresent()) ? param.firstValue() : null;
    }

    public String header(String name) {
        com.github.tomakehurst.wiremock.http.HttpHeader h = request.header(name);
        return (h != null && h.isPresent()) ? h.firstValue() : null;
    }

    public String body() {
        return request.getBodyAsString();
    }

    public String method() {
        return request.getMethod().getName();
    }

    public String pathSegment(int index) {
        List<String> segments = splitPath(request.getUrl());
        return index >= 0 && index < segments.size() ? segments.get(index) : null;
    }

    public Map<String, String> allHeaders() {
        Map<String, String> result = new HashMap<>();
        for (String key : request.getAllHeaderKeys()) {
            result.put(key, request.header(key).firstValue());
        }
        return result;
    }

    private List<String> splitPath(String url) {
        String path = url.split("\\?")[0];
        return java.util.Arrays.stream(path.split("/"))
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toList());
    }
}