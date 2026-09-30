package com.mercur.upgrade.e2e;

import java.time.Duration;

/**
 * Where the deployment under test is and how to reach it. Each setting is a system property ({@code -De2e.baseUrl=...}
 * on the Maven command line), else an environment variable, else the default for the local Rancher Desktop deployment.
 */
final class E2eSettings {

    /** Where requests go: the UI, whose nginx proxies /api like it does for a browser, or the backend directly. */
    static final String BASE_URL = setting("e2e.baseUrl", "E2E_BASE_URL", "http://127.0.0.1:4200");

    /** The backend itself, for the rate-limit check (the UI's nginx has its own, different limit). */
    static final String API_URL = setting("e2e.apiUrl", "E2E_API_URL", "http://127.0.0.1:8080");

    static final String ADMIN_USERNAME = setting("e2e.adminUsername", "UPGRADE_SECURITY_ADMIN_USERNAME", "admin");

    static final String ADMIN_PASSWORD = setting("e2e.adminPassword", "UPGRADE_SECURITY_ADMIN_PASSWORD", "admin");

    /** The kube context value for tests running in a pod: kubectl then uses the pod's service account. */
    static final String IN_CLUSTER = "in-cluster";

    /**
     * Named on every kubectl call, so the infrastructure checks never look at whatever cluster is current;
     * {@value #IN_CLUSTER} inside the cluster.
     */
    static final String KUBE_CONTEXT = setting("e2e.kubeContext", "ACCOUNT_UPGRADE_KUBE_CONTEXT", "rancher-desktop");

    static final String NAMESPACE = setting("e2e.namespace", "E2E_NAMESPACE", "account-upgrade");

    static final String TOPIC = setting("e2e.topic", "UPGRADE_MESSAGING_TOPICS_UPGRADE_REQUESTS", "upgrade-requests");

    static final Duration PROCESSING_TIMEOUT =
            Duration.ofSeconds(Long.parseLong(setting("e2e.processingTimeout", "E2E_PROCESSING_TIMEOUT", "60")));

    private E2eSettings() {
    }

    private static String setting(String property, String environmentVariable, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
