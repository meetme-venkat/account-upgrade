package com.mercur.upgrade.common;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * Inbound account upgrade request (same schema for batch and real-time channels).
 *
 * <p>Only structural constraints are validated at ingestion time. Business constraints
 * (age range, minimum balance, non-empty user name) are eligibility rules evaluated
 * asynchronously, so that failures are recorded and the user is notified.
 */
public record UpgradeRequest(
        @NotBlank(message = "userId is required") String userId,
        String userName,
        Integer age,
        BigDecimal balance,
        @Email(message = "parentEmail must be a valid email address") String parentEmail) {

    public boolean hasParentEmail() {
        return parentEmail != null && !parentEmail.isBlank();
    }
}
