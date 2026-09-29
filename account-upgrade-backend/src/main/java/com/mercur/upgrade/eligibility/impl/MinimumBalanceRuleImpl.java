package com.mercur.upgrade.eligibility.impl;

import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.eligibility.EligibilityProperties;
import com.mercur.upgrade.eligibility.EligibilityRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** Balance must be at least {@code minBalance}. */
@Component
@Order(3)
public class MinimumBalanceRuleImpl implements EligibilityRule {

    private final BigDecimal minBalance;

    public MinimumBalanceRuleImpl(EligibilityProperties properties) {
        this.minBalance = properties.minBalance();
    }

    @Override
    public Optional<String> check(UpgradeRequest request) {
        BigDecimal balance = request.balance();
        if (balance == null) {
            return Optional.of("Balance is required");
        }
        if (balance.compareTo(minBalance) < 0) {
            return Optional.of("Balance must be at least $%s but was $%s"
                    .formatted(minBalance.toPlainString(), balance.toPlainString()));
        }
        return Optional.empty();
    }
}
