package com.vedicmeet.appserver.call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Executes durable callbacks for timers created by Java. Disabled by default. */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "vedicmeet.call", name = "timer-worker-enabled", havingValue = "true")
public class JavaCallTimerWorker {

    private static final Logger log = LoggerFactory.getLogger(JavaCallTimerWorker.class);
    private static final int MAX_BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;

    private final JavaCallTimerService timers;
    private final CallMissedService missed;
    private final CallCompletionService completion;
    private final CallContinuationService continuation;

    public JavaCallTimerWorker(JavaCallTimerService timers, CallMissedService missed,
                               CallCompletionService completion,
                               CallContinuationService continuation) {
        this.timers = timers;
        this.missed = missed;
        this.completion = completion;
        this.continuation = continuation;
    }

    @Scheduled(fixedDelayString = "${vedicmeet.call.timer-poll-ms:500}")
    public void poll() {
        for (int i = 0; i < MAX_BATCH; i++) {
            JavaCallTimerService.ClaimedTimer timer = timers.claimNextDue();
            if (timer == null) return;
            try {
                if (timer.type() == JavaCallTimerService.Type.CALL_MISSED) {
                    missed.handle(timer.roomId(), timer.userId(), timer.consultantId(), timer.callType());
                } else if (timer.type() == JavaCallTimerService.Type.CALL_TIMEOUT) {
                    completion.complete(timer.roomId(), timer.userId(), timer.consultantId(),
                            "completed", "system");
                } else {
                    continuation.afterReconnectWindow(timer.consultantId());
                }
                timers.markCompleted(timer.id());
            } catch (RuntimeException error) {
                log.error("Java call timer failed type={} roomId={} attempt={}",
                        timer.type(), timer.roomId(), timer.attempts(), error);
                timers.retryOrFail(timer, error, MAX_ATTEMPTS);
            }
        }
    }
}
