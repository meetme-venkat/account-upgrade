package com.mercur.upgrade.eligibility;

import java.util.List;

/** Outcome of evaluating all eligibility rules for one request. */
public record EligibilityResult(boolean eligible, List<String> reasons) {

    public EligibilityResult {
        reasons = List.copyOf(reasons);
    }

    public static EligibilityResult fromFailures(List<String> failures) {
        return new EligibilityResult(failures.isEmpty(), failures);
    }
}
