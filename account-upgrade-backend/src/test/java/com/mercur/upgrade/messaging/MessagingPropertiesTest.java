package com.mercur.upgrade.messaging;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Topic names come from configuration and are checked at startup. */
class MessagingPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void applicationYmlDefaultsTheDeadLetterQueueToTheMainTopicPlusDlq() throws IOException {
        assertThat(bindApplicationYml(Map.of()).topics())
                .isEqualTo(new MessagingProperties.Topics("upgrade-requests", "upgrade-requests-dlq"));
    }

    @Test
    void anEnvironmentOverrideOfTheMainTopicAlsoRenamesItsDeadLetterQueue() throws IOException {
        MessagingProperties properties = bindApplicationYml(Map.of("UPGRADE_MESSAGING_TOPICS_UPGRADE_REQUESTS", "prod.upgrade-requests"));

        assertThat(properties.topics())
                .isEqualTo(new MessagingProperties.Topics("prod.upgrade-requests", "prod.upgrade-requests-dlq"));
    }

    @Test
    void theDeadLetterQueueCanBeOverriddenOnItsOwn() throws IOException {
        MessagingProperties properties = bindApplicationYml(Map.of("UPGRADE_MESSAGING_TOPICS_UPGRADE_REQUESTS_DLQ", "shared-dlq"));

        assertThat(properties.topics().upgradeRequestsDlq()).isEqualTo("shared-dlq");
    }

    @Test
    void acceptsLegalTopicNames() {
        assertThat(violations(new MessagingProperties.Topics("env_1.upgrade-requests", "env_1.upgrade-requests-dlq"))).isEmpty();
    }

    @Test
    void rejectsMissingBlankAndIllegalTopicNames() {
        assertThat(violations(new MessagingProperties.Topics(null, "upgrade-requests-dlq"))).isNotEmpty();
        assertThat(violations(new MessagingProperties.Topics("upgrade-requests", " "))).isNotEmpty();
        assertThat(violations(new MessagingProperties.Topics("upgrade requests", "upgrade-requests-dlq"))).isNotEmpty();
        assertThat(violations(new MessagingProperties.Topics("upgrade-requests", "a".repeat(250)))).isNotEmpty();
    }

    @Test
    void rejectsADeadLetterQueueThatIsTheMainTopic() {
        assertThat(violations(new MessagingProperties.Topics("upgrade-requests", "upgrade-requests")))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("upgrade-requests-dlq must be a different topic from upgrade-requests");
    }

    @Test
    void requiresTheTopicsSection() {
        assertThat(validator.validate(new MessagingProperties(4, null))).isNotEmpty();
    }

    private static Set<ConstraintViolation<MessagingProperties>> violations(MessagingProperties.Topics topics) {
        return validator.validate(new MessagingProperties(4, topics));
    }

    /** Binds upgrade.messaging from the real application.yml, with environment variables on top (as at runtime). */
    private static MessagingProperties bindApplicationYml(Map<String, Object> environmentVariables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("upgrade.messaging", MessagingProperties.class).get();
    }
}
