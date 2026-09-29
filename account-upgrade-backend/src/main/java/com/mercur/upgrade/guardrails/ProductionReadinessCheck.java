package com.mercur.upgrade.guardrails;

import com.mercur.upgrade.messaging.kafka.KafkaTopicProperties;
import com.mercur.upgrade.web.CorsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup in the {@code prod} profile when the configuration is unsafe, instead of letting
 * the service run and fail later (or silently lose data).
 *
 * <ul>
 *   <li>A wildcard CORS origin is always rejected.</li>
 *   <li>Kafka topics must survive a broker failure without losing acknowledged writes: replication factor
 *       at least 3, {@code min.insync.replicas} at least 2 and below the replication factor.</li>
 *   <li>The transaction manager must be the JDBC one. Anything else (e.g. a Kafka transaction manager
 *       replacing it) would silently run the decision and outbox writes without a shared transaction.</li>
 *   <li>A disabled rate limit only logs a warning, since it may be enforced at a gateway instead.</li>
 * </ul>
 */
@Component
@Profile("prod")
public class ProductionReadinessCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);

    private final GuardrailProperties guardrails;
    private final CorsProperties cors;
    private final KafkaTopicProperties kafka;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;

    public ProductionReadinessCheck(GuardrailProperties guardrails, CorsProperties cors, KafkaTopicProperties kafka,
                                    ObjectProvider<PlatformTransactionManager> transactionManager) {
        this.guardrails = guardrails;
        this.cors = cors;
        this.kafka = kafka;
        this.transactionManager = transactionManager;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = new ArrayList<>();

        if (cors.allowedOrigins().stream().anyMatch(origin -> origin.contains("*"))) {
            violations.add("upgrade.web.cors.allowed-origins must list exact origins, not a wildcard");
        }

        violations.addAll(kafkaDurabilityViolations());

        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        if (!(manager instanceof DataSourceTransactionManager)) {
            violations.add("the JDBC transaction manager is required, found "
                    + (manager == null ? "none" : manager.getClass().getSimpleName())
                    + ". Decision and outbox writes would not share a transaction");
        }

        if (!guardrails.rateLimit().enabled()) {
            log.warn("upgrade.guardrails.rate-limit.enabled=false: make sure a gateway rate-limits /api");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Production guardrails failed:\n - " + String.join("\n - ", violations));
        }
        log.info("Production guardrails passed");
    }

    private List<String> kafkaDurabilityViolations() {
        List<String> violations = new ArrayList<>();
        if (kafka.replicationFactor() < 3) {
            violations.add("upgrade.messaging.kafka.replication-factor must be at least 3, was "
                    + kafka.replicationFactor());
        }
        if (kafka.minInsyncReplicas() < 2) {
            violations.add("upgrade.messaging.kafka.min-insync-replicas must be at least 2, was "
                    + kafka.minInsyncReplicas() + " (acks=all would otherwise accept writes held by one broker)");
        }
        if (kafka.minInsyncReplicas() >= kafka.replicationFactor()) {
            violations.add("upgrade.messaging.kafka.min-insync-replicas (" + kafka.minInsyncReplicas()
                    + ") must be lower than the replication factor (" + kafka.replicationFactor()
                    + "), or any broker restart stops all writes");
        }
        return violations;
    }
}
