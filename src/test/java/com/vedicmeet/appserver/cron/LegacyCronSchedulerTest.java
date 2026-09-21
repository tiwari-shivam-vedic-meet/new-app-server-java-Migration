package com.vedicmeet.appserver.cron;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class LegacyCronSchedulerTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LegacyCronScheduler.class)
            .withBean(LegacyCronService.class, () -> mock(LegacyCronService.class))
            .withBean(RedisLockService.class, () -> mock(RedisLockService.class));

    @Test
    void absentWithSafeDefaults() {
        context.run(c -> assertFalse(c.containsBean("legacyCronScheduler")));
    }

    @Test
    void absentWhenCronOnButWritesOff() {
        context.withPropertyValues("vedicmeet.cron.enabled=true",
                "vedicmeet.migration.writes-enabled=false")
                .run(c -> assertFalse(c.containsBean("legacyCronScheduler")));
    }

    @Test
    void existsOnlyAfterBothMasterGatesAreOn() {
        context.withPropertyValues("vedicmeet.cron.enabled=true",
                "vedicmeet.migration.writes-enabled=true")
                .run(c -> assertNotNull(c.getBean(LegacyCronScheduler.class)));
    }
}
