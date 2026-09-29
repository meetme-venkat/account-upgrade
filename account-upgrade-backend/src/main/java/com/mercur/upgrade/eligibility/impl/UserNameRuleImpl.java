package com.mercur.upgrade.eligibility.impl;

import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.eligibility.EligibilityRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** User name must be present and not blank. */
@Component
@Order(1)
public class UserNameRuleImpl implements EligibilityRule {

    @Override
    public Optional<String> check(UpgradeRequest request) {
        String userName = request.userName();
        if (userName == null || userName.isBlank()) {
            return Optional.of("User name must not be empty");
        }
        return Optional.empty();
    }
}
