package com.mercur.upgrade.eligibility;

import com.mercur.upgrade.eligibility.rules.AgeRangeRule;
import com.mercur.upgrade.eligibility.rules.MinimumBalanceRule;
import com.mercur.upgrade.eligibility.rules.UserNameRule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static com.mercur.upgrade.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityRulesTest {

    private final EligibilityProperties properties = new EligibilityProperties(18, 23, new BigDecimal("30"));

    @Nested
    class AgeRange {

        private final AgeRangeRule rule = new AgeRangeRule(properties);

        @ParameterizedTest
        @ValueSource(ints = {18, 20, 23})
        void passesWithinInclusiveRange(int age) {
            assertThat(rule.check(request("Alice", age, "50"))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 17, 24, 65})
        void failsOutsideRange(int age) {
            assertThat(rule.check(request("Alice", age, "50")))
                    .hasValue("Age must be between 18 and 23 (inclusive) but was " + age);
        }

        @Test
        void failsWhenAgeMissing() {
            assertThat(rule.check(request("Alice", null, "50"))).hasValue("Age is required");
        }
    }

    @Nested
    class MinimumBalance {

        private final MinimumBalanceRule rule = new MinimumBalanceRule(properties);

        @ParameterizedTest
        @ValueSource(strings = {"30", "30.00", "30.01", "1000000"})
        void passesAtOrAboveMinimum(String balance) {
            assertThat(rule.check(request("Alice", 20, balance))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"29.99", "0", "-5"})
        void failsBelowMinimum(String balance) {
            assertThat(rule.check(request("Alice", 20, balance)))
                    .hasValue("Balance must be at least $30 but was $" + balance);
        }

        @Test
        void failsWhenBalanceMissing() {
            assertThat(rule.check(request("Alice", 20, null))).hasValue("Balance is required");
        }
    }

    @Nested
    class UserName {

        private final UserNameRule rule = new UserNameRule();

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void failsWhenNullOrBlank(String userName) {
            assertThat(rule.check(request(userName, 20, "50"))).hasValue("User name must not be empty");
        }

        @Test
        void passesWhenPresent() {
            assertThat(rule.check(request("Alice", 20, "50"))).isEmpty();
        }
    }
}
