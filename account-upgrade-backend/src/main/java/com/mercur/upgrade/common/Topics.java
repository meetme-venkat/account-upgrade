package com.mercur.upgrade.common;

/** Topic names shared by producers and consumers. */
public final class Topics {

    public static final String UPGRADE_REQUESTS = "upgrade-requests";
    /** Where records that exhausted their retries are parked (the recoverer's default "-dlt" suffix). */
    public static final String UPGRADE_REQUESTS_DLT = UPGRADE_REQUESTS + "-dlt";

    private Topics() {
    }
}
