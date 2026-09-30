package com.mercur.upgrade;

import com.mercur.upgrade.security.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the application against a PostgreSQL container. Subclasses add the Kafka they need (see
 * {@link IntegrationTestSupport}). The scheduled outbox relay is slowed to once an hour so tests drive it
 * explicitly; tables are emptied before every test. API calls need an access token: wrap them in
 * {@link #authorized}.
 */
@SpringBootTest(properties = "upgrade.notification.relay.interval=1h")
public abstract class PostgresContainerSupport {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private AccessTokenService accessTokens;

    /** Adds a valid access token for the administrator, as the frontend does after logging in. */
    protected MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokens.issue("admin").value());
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = TestContainers.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void emptyTables() {
        // Sequences keep counting: Hibernate holds blocks of outbox ids in memory, which a restart would hand out again.
        jdbc.sql("TRUNCATE processed_upgrades, notification_outbox").update();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
