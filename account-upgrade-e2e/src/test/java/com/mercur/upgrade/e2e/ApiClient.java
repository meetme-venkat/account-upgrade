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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP calls to the deployment, as a client makes them. Never throws on 4xx/5xx: the status is what gets checked.
 * A client created with {@code retryWhenThrottled} waits and retries a 429 (rate limit) after its Retry-After, as a
 * well-behaved client does; the default client returns it, for the tests of the rate limit itself.
 */
final class ApiClient {

    static final ObjectMapper JSON = new ObjectMapper();

    // A batch of 10,000 takes about a minute to be accepted (each request is published to Kafka).
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MAX_THROTTLED = Duration.ofMinutes(2);
    private static final int TOO_MANY_REQUESTS = 429;

    private final String baseUrl;
    private final boolean retryWhenThrottled;
    private final HttpClient http;

    ApiClient(String baseUrl) {
        this(baseUrl, false);
    }

    ApiClient(String baseUrl, boolean retryWhenThrottled) {
        this.baseUrl = baseUrl;
        this.retryWhenThrottled = retryWhenThrottled;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** The status, headers and body (raw, and parsed when it is JSON) of one call. */
    record Response(int status, HttpHeaders headers, String body, JsonNode json) {

        /** Status and the start of the body, for reports. */
        String summary() {
            String text = body.replaceAll("\\s+", " ").strip();
            return "HTTP " + status + (text.isEmpty() ? "" : " " + (text.length() > 150 ? text.substring(0, 150) + "..." : text));
        }
    }

    Response get(String path) {
        return get(path, Map.of());
    }

    Response get(String path, Map<String, String> headers) {
        return send("GET", path, null, null, headers);
    }

    /** {@code body} is sent as is when it is a String (to send malformed JSON), otherwise serialised to JSON. */
    Response post(String path, Object body, Map<String, String> headers) {
        return send("POST", path, body instanceof String raw ? raw : toJson(body), "application/json", headers);
    }

    /** Any call: {@code body} null sends none, {@code contentType} null sends no Content-Type. */
    Response send(String method, String path, String body, String contentType, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path, headers)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        HttpRequest request = builder.build();
        Instant giveUp = Instant.now().plus(MAX_THROTTLED);
        while (true) {
            Response response = send(request);
            if (response.status() != TOO_MANY_REQUESTS || !retryWhenThrottled || Instant.now().isAfter(giveUp)) {
                return response;
            }
            pause(retryAfter(response));
        }
    }

    /** A GET that is not waited for, to send many at once. Resolves to the status code. Never retried. */
    CompletableFuture<Integer> getAsync(String path) {
        return http.sendAsync(request(path, Map.of()).GET().build(), HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode);
    }

    /** A JSON POST that is not waited for, to send many at once. Resolves to the status code. Never retried. */
    CompletableFuture<Integer> postAsync(String path, String json, Map<String, String> headers) {
        HttpRequest request = request(path, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
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
            return new Response(response.statusCode(), response.headers(), response.body(), json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " returned invalid JSON", e);
        }
        catch (IOException e) {
            throw new UncheckedIOException(request.method() + " " + request.uri() + " failed", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static Duration retryAfter(Response response) {
        String seconds = response.headers().firstValue("Retry-After").orElse("1");
        return Duration.ofSeconds(seconds.matches("\\d{1,3}") ? Math.max(1, Long.parseLong(seconds)) : 1);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
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
