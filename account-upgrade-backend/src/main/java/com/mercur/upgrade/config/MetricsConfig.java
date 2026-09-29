package com.mercur.upgrade.config;

import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Operational gauges, exposed at {@code /actuator/metrics/<name>}. Notification metrics are registered by
 * {@code OutboxRelay}, the dead-letter counter by {@code KafkaConfig}; Kafka client metrics such as consumer
 * lag ({@code kafka.consumer.fetch.manager.records.lag.max}) are bound automatically.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    @Bean
    MeterBinder upgradeMetrics(ProcessedUpgradeRepository repository) {
        return registry -> Gauge.builder("upgrade.processed", repository, ProcessedUpgradeRepository::count)
                .description("Processed upgrade requests stored").register(registry);
    }
}
