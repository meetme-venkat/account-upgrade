package com.mercur.upgrade.common;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Inbound account upgrade request (same schema for batch and real-time channels).
 *
 * <p>Only structural constraints are validated at ingestion time. Business constraints
 * (age range, minimum balance, non-empty user name) are eligibility rules evaluated
 * asynchronously, so that failures are recorded and the user is notified.
 *
 * <p>Length limits are a guardrail: these values end up in logs, emails and storage.
 */
public record UpgradeRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 64, message = "userId must be at most 64 characters") String userId,
        @Size(max = 100, message = "userName must be at most 100 characters") String userName,
        Integer age,
        BigDecimal balance,
        @Email(message = "parentEmail must be a valid email address")
        @Size(max = 254, message = "parentEmail must be at most 254 characters") String parentEmail) {

    public boolean hasParentEmail() {
        return parentEmail != null && !parentEmail.isBlank();
    }
}
