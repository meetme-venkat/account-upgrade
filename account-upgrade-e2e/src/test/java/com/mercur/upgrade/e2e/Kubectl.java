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

    /** A Kafka command-line tool, run in the first broker's pod against itself. */
    static String kafka(String tool, String... args) {
        List<String> command = new ArrayList<>(List.of(
                "exec", "kafka-0", "--", "/opt/kafka/bin/" + tool, "--bootstrap-server", "localhost:9092"));
        command.addAll(List.of(args));
        return run(command.toArray(String[]::new));
    }
}
