package com.mercur.upgrade.eligibility;

import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.eligibility.impl.AgeRangeRuleImpl;
import com.mercur.upgrade.eligibility.impl.MinimumBalanceRuleImpl;
import com.mercur.upgrade.eligibility.impl.UserNameRuleImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EligibilityServiceTest {

    @Mock
    private EligibilityRule firstRule;

    @Mock
    private EligibilityRule secondRule;

    @Test
    void eligibleWhenEveryRulePasses() {
        UpgradeRequest request = eligible();
        when(firstRule.check(request)).thenReturn(Optional.empty());
        when(secondRule.check(request)).thenReturn(Optional.empty());

        EligibilityResult result = new EligibilityService(List.of(firstRule, secondRule)).evaluate(request);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void evaluatesAllRulesAndCollectsEveryFailureReason() {
        UpgradeRequest request = eligible();
        when(firstRule.check(request)).thenReturn(Optional.of("first failed"));
        when(secondRule.check(request)).thenReturn(Optional.of("second failed"));

        EligibilityResult result = new EligibilityService(List.of(firstRule, secondRule)).evaluate(request);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly("first failed", "second failed");
        verify(secondRule).check(request); // no short-circuit after the first failure
    }

    @Test
    void realRulesRejectRequestViolatingEveryConstraint() {
        EligibilityProperties properties = new EligibilityProperties(18, 23, new BigDecimal("30"));
        EligibilityService service = new EligibilityService(List.of(
                new UserNameRuleImpl(), new AgeRangeRuleImpl(properties), new MinimumBalanceRuleImpl(properties)));

        EligibilityResult result = service.evaluate(request("", 30, "10"));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(
                "User name must not be empty",
                "Age must be between 18 and 23 (inclusive) but was 30",
                "Balance must be at least $30 but was $10");
    }
}
