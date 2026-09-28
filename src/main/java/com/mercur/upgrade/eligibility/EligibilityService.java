package com.mercur.upgrade.eligibility;

import com.mercur.upgrade.common.UpgradeRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Evaluates every registered {@link EligibilityRule} and collects all failure reasons
 * (rather than stopping at the first one) so the user learns everything that needs fixing.
 */
@Service
public class EligibilityService {

    private final List<EligibilityRule> rules;

    public EligibilityService(List<EligibilityRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public EligibilityResult evaluate(UpgradeRequest request) {
        List<String> failures = rules.stream()
                .map(rule -> rule.check(request))
                .flatMap(Optional::stream)
                .toList();
        return EligibilityResult.fromFailures(failures);
    }
}
