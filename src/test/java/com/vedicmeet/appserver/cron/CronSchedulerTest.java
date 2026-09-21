package com.vedicmeet.appserver.cron;

import com.vedicmeet.appserver.payment.PaymentReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Safety tests for cron feature gates. */
class CronSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CronScheduler.class)
            .withBean(RechargeOfferService.class, () -> mock(RechargeOfferService.class))
            .withBean(RedisLockService.class, () -> mock(RedisLockService.class))
            .withBean(PaymentReconciliationService.class, () -> mock(PaymentReconciliationService.class));

    @Test
    void schedulerBeanIsAbsentWhenAllGatesUseSafeDefaults() {
        contextRunner.run(context ->
                org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("cronScheduler")));
    }

    @Test
    void schedulerBeanIsAbsentWhenCronIsOnButMigrationWritesAreOff() {
        contextRunner
                .withPropertyValues("vedicmeet.cron.enabled=true", "vedicmeet.migration.writes-enabled=false")
                .run(context ->
                        org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("cronScheduler")));
    }

    @Test
    void schedulerBeanExistsOnlyWhenCronAndMigrationWritesAreBothOn() {
        contextRunner
                .withPropertyValues("vedicmeet.cron.enabled=true", "vedicmeet.migration.writes-enabled=true")
                .run(context ->
                        org.junit.jupiter.api.Assertions.assertNotNull(context.getBean(CronScheduler.class)));
    }

    @Test
    void paymentReconciliationDoesNothingWhenItsDedicatedGateIsOff() {
        RechargeOfferService offers = mock(RechargeOfferService.class);
        RedisLockService lock = mock(RedisLockService.class);
        PaymentReconciliationService reconciliation = mock(PaymentReconciliationService.class);
        CronScheduler scheduler = new CronScheduler(offers, lock, reconciliation, false);

        scheduler.reconcilePendingPayments();

        verify(lock, never()).withCronLock(eq("reconcilePendingPayments"), any(Runnable.class), eq(240L));
        verify(reconciliation, never()).runReconciliation();
    }

    @Test
    void paymentReconciliationRunsUnderTheDistributedLockWhenGateIsOn() {
        RechargeOfferService offers = mock(RechargeOfferService.class);
        RedisLockService lock = mock(RedisLockService.class);
        PaymentReconciliationService reconciliation = mock(PaymentReconciliationService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(lock).withCronLock(eq("reconcilePendingPayments"), any(Runnable.class), eq(240L));
        CronScheduler scheduler = new CronScheduler(offers, lock, reconciliation, true);

        scheduler.reconcilePendingPayments();

        verify(lock).withCronLock(eq("reconcilePendingPayments"), any(Runnable.class), eq(240L));
        verify(reconciliation).runReconciliation();
    }
}
