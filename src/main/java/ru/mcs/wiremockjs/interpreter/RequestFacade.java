package ru.mcs.wiremockjs.interpreter;

import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

// Единственная точка доступа скрипта к реальному Request WireMock.
// Никаких прямых ссылок на внутренние объекты WireMock не отдаётся наружу.
public class RequestFacade {

    private final Request request;

    public RequestFacade(Request request) {
        this.request = request;
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

    public String url() {
        return request.getUrl();
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