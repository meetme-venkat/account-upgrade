package com.mercur.upgrade.eligibility;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/** Externalised thresholds for the eligibility rules. */
@ConfigurationProperties("upgrade.eligibility")
public record EligibilityProperties(
        @DefaultValue("18") int minAge,
        @DefaultValue("23") int maxAge,
        @DefaultValue("30") BigDecimal minBalance) {
}
