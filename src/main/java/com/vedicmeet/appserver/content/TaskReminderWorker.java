package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Polls durable task reminders; disabled unless both worker and migration-write gates are enabled. */
@Component
@EnableScheduling
@ConditionalOnExpression("'${vedicmeet.task-reminder.worker-enabled:false}' == 'true' and "
        + "'${vedicmeet.migration.writes-enabled:false}' == 'true'")
public class TaskReminderWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskReminderWorker.class);
    private final TaskReminderService reminders;
    private final PushNotificationService push;

    public TaskReminderWorker(TaskReminderService reminders, PushNotificationService push) {
        this.reminders = reminders;
        this.push = push;
    }

    @Scheduled(fixedDelayString = "${vedicmeet.task-reminder.poll-ms:1000}")
    @SuppressWarnings("unchecked")
    public void poll() {
        for (int i = 0; i < 20; i++) {
            Document job = reminders.claimNextDue();
            if (job == null) return;
            try {
                List<String> tokens = job.getList("tokens", String.class, List.of());
                for (String token : tokens) push.sendNotificationAndCons(
                        job.getString("userType"), token,
                        job.getString("reminderType") + ":" + job.getString("title"),
                        Map.of(), "Vedic", "task");
                reminders.complete(job.getObjectId("_id"));
            } catch (RuntimeException error) {
                log.error("task reminder failed id={} attempt={}", job.get("_id"), job.get("attempts"), error);
                reminders.retryOrFail(job, error, 5);
            }
        }
    }
}
