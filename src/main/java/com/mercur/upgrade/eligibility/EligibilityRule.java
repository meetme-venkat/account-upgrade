package com.mercur.upgrade.eligibility;

import com.mercur.upgrade.common.UpgradeRequest;

import java.util.Optional;

/**
 * A single eligibility check. New rules are added by declaring another Spring bean
 * implementing this interface; {@link EligibilityService} picks them up automatically.
 */
public interface EligibilityRule {

    /**
     * @return a human-readable failure reason, or empty if the request satisfies the rule
     */
    Optional<String> check(UpgradeRequest request);
}
