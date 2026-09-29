package com.mercur.upgrade.guardrails;

import com.mercur.upgrade.messaging.kafka.KafkaTopicProperties;
import com.mercur.upgrade.security.AuthProperties;
import com.mercur.upgrade.web.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionReadinessCheckTest {

    private static final GuardrailProperties RATE_LIMIT_ON =
            new GuardrailProperties(new GuardrailProperties.RateLimit(true, 20, 40, 10_000));

    /** Safe settings by default; each test changes one thing. */
    private List<String> origins = List.of("https://app.example.com");
    private KafkaTopicProperties kafka = new KafkaTopicProperties(3, 2, 3);
    private PlatformTransactionManager transactionManager = new JdbcTransactionManager(mock(DataSource.class));
    private String jwtSecret = "a-production-secret-of-at-least-32-bytes";

    private ProductionReadinessCheck check() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(transactionManager);
        AuthProperties auth = new AuthProperties(new AuthProperties.Admin("admin", "admin"),
                new AuthProperties.Jwt(jwtSecret, "account-upgrade-backend", Duration.ofHours(1)));
        return new ProductionReadinessCheck(RATE_LIMIT_ON, new CorsProperties(origins), kafka, provider, auth);
    }

    @Test
    void passesWithDurableTopicsExactOriginsAndTheJdbcTransactionManager() {
        assertThatCode(check()::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void refusesWildcardCors() {
        origins = List.of("*");

        assertThatThrownBy(check()::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("replication"));
    }

    @Test
    void refusesKafkaTopicsThatCannotSurviveABrokerFailure() {
        kafka = new KafkaTopicProperties(1, 1, 4);

        assertThatThrownBy(check()::afterPropertiesSet)
                .hasMessageContaining("replication-factor must be at least 3, was 1")
                .hasMessageContaining("min-insync-replicas must be at least 2, was 1");
    }

    @Test
    void refusesMinInsyncReplicasEqualToTheReplicationFactor() {
        kafka = new KafkaTopicProperties(3, 3, 4);

        assertThatThrownBy(check()::afterPropertiesSet).hasMessageContaining("must be lower than the replication factor");
    }

    @Test
    void refusesAnythingButTheJdbcTransactionManager() {
        transactionManager = mock(PlatformTransactionManager.class);
        assertThatThrownBy(check()::afterPropertiesSet).hasMessageContaining("JDBC transaction manager is required");

        transactionManager = null;
        assertThatThrownBy(check()::afterPropertiesSet).hasMessageContaining("found none");
    }

    @Test
    void refusesTheDevelopmentJwtSigningKey() {
        jwtSecret = AuthProperties.DEVELOPMENT_SECRET;

        assertThatThrownBy(check()::afterPropertiesSet)
                .hasMessageContaining("upgrade.security.jwt.secret is the development key");
    }

    @Test
    void reportsEveryViolationAtOnce() {
        origins = List.of("*");
        kafka = new KafkaTopicProperties(1, 1, 4);
        transactionManager = null;

        assertThatThrownBy(check()::afterPropertiesSet)
                .hasMessageContaining("wildcard")
                .hasMessageContaining("replication-factor")
                .hasMessageContaining("transaction manager");
    }
}
