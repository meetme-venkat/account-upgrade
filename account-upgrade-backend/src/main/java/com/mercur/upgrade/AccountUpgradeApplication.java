package com.mercur.upgrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Account upgrade service.
 *
 * <p>Flow: REST ingestion -> "upgrade-requests" topic -> eligibility processing
 * -> notification -> persistence -> REST retrieval. See README.md for build/run instructions.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AccountUpgradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountUpgradeApplication.class, args);
    }
}
