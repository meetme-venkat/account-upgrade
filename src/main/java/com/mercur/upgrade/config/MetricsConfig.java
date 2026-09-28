package com.mercur.upgrade.config;

import com.mercur.upgrade.messaging.inmemory.InMemoryMessageBroker;
import com.mercur.upgrade.notification.InMemoryEmailSender;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Operational gauges, exposed at {@code /actuator/metrics/<name>}. */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    @Bean
    MeterBinder upgradeMetrics(ProcessedUpgradeRepository repository,
                               InMemoryEmailSender emailSender,
                               ObjectProvider<InMemoryMessageBroker> broker) {
        return registry -> {
            Gauge.builder("upgrade.processed", repository, ProcessedUpgradeRepository::count)
                    .description("Processed upgrade requests stored").register(registry);
            FunctionCounter.builder("upgrade.notifications.sent", emailSender, InMemoryEmailSender::totalSent)
                    .description("Simulated emails sent").register(registry);
            broker.ifAvailable(b -> {
                Gauge.builder("upgrade.broker.pending", b, InMemoryMessageBroker::pendingCount)
                        .description("Events queued or being processed").register(registry);
                FunctionCounter.builder("upgrade.broker.dead-letters", b, InMemoryMessageBroker::deadLetterCount)
                        .description("Events that exhausted their delivery attempts").register(registry);
            });
        };
    }
}
