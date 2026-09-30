package com.mercur.upgrade.e2e;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** HTTP calls to the deployment, as a client makes them. Never throws on 4xx/5xx: the status is what gets checked. */
final class ApiClient {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final HttpClient http;

    ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /** The status, headers and (for a JSON response) body of one call. */
    record Response(int status, HttpHeaders headers, JsonNode json) {
    }

    Response get(String path) {
        return get(path, Map.of());
    }

    Response get(String path, Map<String, String> headers) {
        return send(request(path, headers).GET().build());
    }

    /** {@code body} is sent as is when it is a String (to send malformed JSON), otherwise serialised to JSON. */
    Response post(String path, Object body, Map<String, String> headers) {
        String text = body instanceof String raw ? raw : toJson(body);
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(text))
                .build());
    }

    /** A GET that is not waited for, to send many at once. Resolves to the status code. */
    CompletableFuture<Integer> getAsync(String path) {
        return http.sendAsync(request(path, Map.of()).GET().build(), HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode);
    }

    private HttpRequest.Builder request(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT);
        headers.forEach(builder::header);
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
            JsonNode json = isJson && !response.body().isEmpty() ? JSON.readTree(response.body()) : null;
            return new Response(response.statusCode(), response.headers(), json);
        }
        catch (IOException e) {
            throw new UncheckedIOException(request.method() + " " + request.uri() + " failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static String toJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
