package com.mercur.upgrade.notification;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the {@link OutboxRelay} schedules. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxConfig {
}
