package com.mercur.upgrade;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the application against a PostgreSQL container. Subclasses add the Kafka they need (see
 * {@link IntegrationTestSupport}). The scheduled outbox relay is slowed to once an hour so tests drive it
 * explicitly; tables are emptied before every test.
 */
@SpringBootTest(properties = "upgrade.notification.relay.interval=1h")
public abstract class PostgresContainerSupport {

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = TestContainers.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE processed_upgrades, notification_outbox RESTART IDENTITY").update();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
