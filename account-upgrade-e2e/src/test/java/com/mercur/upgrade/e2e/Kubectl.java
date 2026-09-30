package com.mercur.upgrade.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs kubectl against the deployment's context and namespace. Read-only use: get, and exec of query tools. */
final class Kubectl {

    private static final long TIMEOUT_SECONDS = 60;

    private Kubectl() {
    }

    /** Stdout of {@code kubectl <args>}; fails the test with kubectl's error output if the command fails. */
    static String run(String... args) {
        List<String> command = new ArrayList<>(List.of("kubectl", "--namespace", E2eSettings.NAMESPACE));
        if (!E2eSettings.KUBE_CONTEXT.equals(E2eSettings.IN_CLUSTER)) {
            command.addAll(List.of("--context", E2eSettings.KUBE_CONTEXT));
        }
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            // Both streams drain at once, so neither pipe can fill up and block kubectl.
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            String out = read(process.getInputStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError(String.join(" ", command) + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(String.join(" ", command) + " failed: " + err.join().strip());
            }
            return out.strip();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot run kubectl (is it on PATH?)", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One query result of psql in the PostgreSQL pod. */
    static String sql(String query) {
        return run("exec", "postgres-0", "--", "psql", "-U", "postgres", "-d", "account_upgrade", "-tAc", query);
    }

    /** A count from psql, e.g. {@code sqlCount("select count(*) from ...")}. */
    static long sqlCount(String query) {
        return Long.parseLong(sql(query));
    }

    /** Messages on the dead-letter topic, over all partitions (output: one topic:partition:offset line each). */
    static long deadLetters() {
        return kafka("kafka-get-offsets.sh", "--topic", E2eSettings.TOPIC + "-dlq")
                .lines().filter(line -> !line.isBlank())
                .mapToLong(line -> Long.parseLong(line.split(":")[2].strip()))
                .sum();
    }

    /** Names of the running pods of a component (app.kubernetes.io/component). */
    static List<String> pods(String component) {
        String names = run("get", "pods", "--selector", "app.kubernetes.io/component=" + component,
                "--field-selector", "status.phase=Running", "-o", "jsonpath={.items[*].metadata.name}");
        return names.isBlank() ? List.of() : List.of(names.split("\\s+"));
    }

    /** One value of a backend pod's actuator metric, read inside the pod (each pod has its own). */
    static double backendMetric(String pod, String metricAndTags) {
        String json = run("exec", pod, "--", "wget", "-qO-", "http://127.0.0.1:8080/actuator/metrics/" + metricAndTags);
        try {
            return ApiClient.JSON.readTree(json).path("measurements").get(0).path("value").asDouble();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A Kafka command-line tool, run in the first broker's pod against itself. */
    static String kafka(String tool, String... args) {
        List<String> command = new ArrayList<>(List.of(
                "exec", "kafka-0", "--", "/opt/kafka/bin/" + tool, "--bootstrap-server", "localhost:9092"));
        command.addAll(List.of(args));
        return run(command.toArray(String[]::new));
    }
}
