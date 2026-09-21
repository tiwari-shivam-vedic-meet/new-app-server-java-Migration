package com.vedicmeet.appserver.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** Scheduling registry for every active non-payment job in Node jobs.js. */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${vedicmeet.cron.enabled:false}' == 'true' and "
        + "'${vedicmeet.migration.writes-enabled:false}' == 'true'")
public class LegacyCronScheduler {

    private static final Logger log = LoggerFactory.getLogger(LegacyCronScheduler.class);

    private final LegacyCronService jobs;
    private final RedisLockService locks;
    private final boolean maintenanceEnabled;
    private final boolean missedRetryEnabled;
    private final boolean fixedSessionEnabled;
    private final boolean nextAvailableEnabled;
    private final boolean uninstallEnabled;
    private final boolean pabblyEnabled;
    private final boolean interaktEnabled;

    public LegacyCronScheduler(LegacyCronService jobs, RedisLockService locks,
            @Value("${vedicmeet.cron.maintenance-enabled:false}") boolean maintenanceEnabled,
            @Value("${vedicmeet.cron.missed-retry-enabled:false}") boolean missedRetryEnabled,
            @Value("${vedicmeet.cron.fixed-session-enabled:false}") boolean fixedSessionEnabled,
            @Value("${vedicmeet.cron.next-available-enabled:false}") boolean nextAvailableEnabled,
            @Value("${vedicmeet.cron.uninstall-enabled:false}") boolean uninstallEnabled,
            @Value("${vedicmeet.cron.pabbly-enabled:false}") boolean pabblyEnabled,
            @Value("${vedicmeet.cron.interakt-enabled:false}") boolean interaktEnabled) {
        this.jobs = jobs;
        this.locks = locks;
        this.maintenanceEnabled = maintenanceEnabled;
        this.missedRetryEnabled = missedRetryEnabled;
        this.fixedSessionEnabled = fixedSessionEnabled;
        this.nextAvailableEnabled = nextAvailableEnabled;
        this.uninstallEnabled = uninstallEnabled;
        this.pabblyEnabled = pabblyEnabled;
        this.interaktEnabled = interaktEnabled;
    }

    @Scheduled(cron = "${vedicmeet.cron.daily-maintenance:0 0 0 * * *}", zone = "Asia/Kolkata")
    public void dailyMaintenance() {
        run("every-24-hours-ops", maintenanceEnabled, jobs::resetDailyLimits, 300);
    }

    @Scheduled(cron = "${vedicmeet.cron.monthly-maintenance:0 0 0 L * *}", zone = "Asia/Kolkata")
    public void monthlyMaintenance() {
        run("every-month-end-ops", maintenanceEnabled, jobs::resetMonthlyFlagLimits, 300);
    }

    @Scheduled(cron = "${vedicmeet.cron.missed-retry:0 */5 * * * *}", zone = "Asia/Kolkata")
    public void retryMissedCalls() {
        run("consultant-availability-status-reset", missedRetryEnabled, jobs::retryMissedCalls, 240);
    }

    @Scheduled(cron = "${vedicmeet.cron.fixed-session:0 */5 * * * *}", zone = "Asia/Kolkata")
    public void fixedSessions() {
        run("session-book-notify", fixedSessionEnabled, jobs::processFixedSessions, 240);
    }

    @Scheduled(cron = "${vedicmeet.cron.next-available:0 */5 * * * *}", zone = "Asia/Kolkata")
    public void nextAvailable() {
        run("consultant-next-available-time-notifier", nextAvailableEnabled,
                jobs::notifyUpcomingConsultants, 240);
    }

    @Scheduled(cron = "${vedicmeet.cron.pabbly-new-users:0 0 * * * *}", zone = "Asia/Kolkata")
    public void pabblyNewUsers() {
        run("whatsapp-ai-sensy", pabblyEnabled, jobs::enqueueNewUsersForPabbly, 300);
    }

    @Scheduled(cron = "${vedicmeet.cron.pabbly-noon:0 0 12 * * *}", zone = "Asia/Kolkata")
    public void pabblyFreeConsultsNoon() {
        LocalDateTime end = LocalDateTime.of(jobs.nowIst().toLocalDate(), LocalTime.NOON);
        LocalDateTime start = end.minusDays(1).withHour(19);
        run("send-free-consult-completed-users-to-pabbly", pabblyEnabled,
                () -> jobs.enqueueFreeConsultsForPabbly(start, end), 600);
    }

    @Scheduled(cron = "${vedicmeet.cron.pabbly-evening:0 0 19 * * *}", zone = "Asia/Kolkata")
    public void pabblyFreeConsultsEvening() {
        LocalDateTime end = LocalDateTime.of(jobs.nowIst().toLocalDate(), LocalTime.of(19, 0));
        LocalDateTime start = end.withHour(12);
        run("send-free-consult-completed-users-to-pabbly-evening", pabblyEnabled,
                () -> jobs.enqueueFreeConsultsForPabbly(start, end), 600);
    }

    @Scheduled(cron = "${vedicmeet.cron.uninstall-detection:0 0 2 * * *}", zone = "Asia/Kolkata")
    public void uninstallDetection() {
        run("detect-app-uninstalls", uninstallEnabled, jobs::detectAppUninstalls, 1800);
    }

    @Scheduled(cron = "${vedicmeet.cron.interakt-unconsulted:0 0 */6 * * *}", zone = "Asia/Kolkata")
    public void interaktUnconsulted() {
        run("user-registered-last-6-hours-not-taken-consultation", interaktEnabled,
                jobs::enqueueUnconsultedUsersForInterakt, 1200);
    }

    @Scheduled(cron = "${vedicmeet.cron.interakt-one-consult:0 5 */6 * * *}", zone = "Asia/Kolkata")
    public void interaktOneConsult() {
        run("user-first-consult-done-but-second-not-taken-after-6-hours", interaktEnabled,
                jobs::enqueueOneConsultUsersForInterakt, 1200);
    }

    private void run(String name, boolean enabled, Runnable task, long ttlSeconds) {
        if (!enabled) {
            log.debug("[CRON] {} remains disabled", name);
            return;
        }
        locks.withCronLock(name, task, ttlSeconds);
    }
}
