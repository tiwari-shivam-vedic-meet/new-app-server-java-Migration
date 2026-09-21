package com.vedicmeet.appserver.cron;

import com.vedicmeet.appserver.payment.PaymentReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Date;

/**
 * Spring cron infrastructure for the ported Node jobs (utils/functions/jobs.js registry).
 *
 * <p>GATED OFF by default: the whole scheduler (and {@code @EnableScheduling}) only exists when
 * {@code vedicmeet.cron.enabled=true}, so on a normal shadow launch NO cron fires and nothing writes to
 * the shared DB. Each job runs under the Redis cron lock ({@link RedisLockService#withCronLock}) so only
 * one instance executes it — matching Node's {@code withRedisLock} single-runner guarantee. Cron
 * expressions are configurable; defaults mirror the Node schedules (6-field Spring form).</p>
 *
 * <p>Currently wires the faithful {@code disableExpiredRechargeOffers} job; the larger notification jobs
 * (session-starting-in-10-min, consultant-online reminders) are ported incrementally behind this gate.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${vedicmeet.cron.enabled:false}' == 'true' and "
        + "'${vedicmeet.migration.writes-enabled:false}' == 'true'")
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final RechargeOfferService rechargeOffers;
    private final RedisLockService redisLock;
    private final PaymentReconciliationService reconciliation;
    private final boolean paymentReconciliationEnabled;

    public CronScheduler(RechargeOfferService rechargeOffers, RedisLockService redisLock,
                         PaymentReconciliationService reconciliation,
                         @Value("${vedicmeet.cron.payment-reconcile-enabled:false}")
                         boolean paymentReconciliationEnabled) {
        this.rechargeOffers = rechargeOffers;
        this.redisLock = redisLock;
        this.reconciliation = reconciliation;
        this.paymentReconciliationEnabled = paymentReconciliationEnabled;
    }

    /** Node "0 0 * * *" daily → disable expired recharge offers, single-runner via the cron lock. */
    @Scheduled(cron = "${vedicmeet.cron.recharge-offer:0 */5 * * * *}", zone = "Asia/Kolkata")
    public void disableExpiredRechargeOffers() {
        redisLock.withCronLock("disableExpiredRechargeOffers",
                () -> rechargeOffers.disableExpiredRechargeOffers(new Date()), 300);
        log.debug("[CRON] disableExpiredRechargeOffers tick handled");
    }

    /** Node every-5-min cron → reconcile pending PhonePe/Paytm payments (poll gateway then confirm on success). */
    @Scheduled(cron = "${vedicmeet.cron.payment-reconcile:0 */5 * * * *}", zone = "Asia/Kolkata")
    public void reconcilePendingPayments() {
        if (!paymentReconciliationEnabled) {
            log.debug("[CRON] payment reconciliation remains disabled");
            return;
        }
        redisLock.withCronLock("reconcilePendingPayments",
                () -> { int n = reconciliation.runReconciliation(); log.debug("[CRON] reconciled {} payments", n); }, 240);
    }
}
