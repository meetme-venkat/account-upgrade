package com.mercur.upgrade.eligibility.rules;

import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.eligibility.EligibilityProperties;
import com.mercur.upgrade.eligibility.EligibilityRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** User must be between {@code minAge} and {@code maxAge}, inclusive. */
@Component
@Order(2)
public class AgeRangeRule implements EligibilityRule {

    private final int minAge;
    private final int maxAge;

    public AgeRangeRule(EligibilityProperties properties) {
        this.minAge = properties.minAge();
        this.maxAge = properties.maxAge();
    }

    @Override
    public Optional<String> check(UpgradeRequest request) {
        Integer age = request.age();
        if (age == null) {
            return Optional.of("Age is required");
        }
        if (age < minAge || age > maxAge) {
            return Optional.of("Age must be between %d and %d (inclusive) but was %d".formatted(minAge, maxAge, age));
        }
        return Optional.empty();
    }
}
