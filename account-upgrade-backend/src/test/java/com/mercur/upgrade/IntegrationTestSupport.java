package com.mercur.upgrade;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that start the application: PostgreSQL and a single Kafka broker, both as containers
 * (needs a Docker engine, e.g. Docker Desktop or Rancher Desktop with dockerd). The containers are shared
 * by all test classes in the JVM.
 */
public abstract class IntegrationTestSupport extends PostgresContainerSupport {

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", TestContainers.kafka()::getBootstrapServers);
    }
}
