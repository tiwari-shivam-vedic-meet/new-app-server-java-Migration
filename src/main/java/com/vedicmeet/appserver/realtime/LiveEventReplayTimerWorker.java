package com.vedicmeet.appserver.realtime;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Executes the 10-second idle / 5-second message replay loop used by /live_event. */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "vedicmeet.live-event", name = "timer-worker-enabled", havingValue = "true")
public class LiveEventReplayTimerWorker {

    private static final Logger log = LoggerFactory.getLogger(LiveEventReplayTimerWorker.class);
    private static final int MAX_BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;

    private final LiveEventReplayTimerService timers;
    private final LiveEventChatService chat;
    private final SocketEventPublisher events;

    public LiveEventReplayTimerWorker(LiveEventReplayTimerService timers, LiveEventChatService chat,
                                      SocketEventPublisher events) {
        this.timers = timers;
        this.chat = chat;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${vedicmeet.live-event.timer-poll-ms:500}")
    public void poll() {
        for (int i = 0; i < MAX_BATCH; i++) {
            LiveEventReplayTimerService.Claimed timer = timers.claimNextDue();
            if (timer == null) return;
            try {
                List<Document> messages = lastTen(chat.getMessages(timer.eventId()));
                if (messages.isEmpty()) {
                    timers.markCompleted(timer);
                } else if (timer.phase() == LiveEventReplayTimerService.Phase.IDLE) {
                    timers.scheduleNext(timer, LiveEventReplayTimerService.Phase.TICK, 0, 0);
                } else if (timer.index() < messages.size()) {
                    events.emitToRoom("live_event", timer.eventId(), SocketEvents.RECEIVE_MESSAGE,
                            messages.get(timer.index()));
                    if (timer.index() + 1 < messages.size()) {
                        timers.scheduleNext(timer, LiveEventReplayTimerService.Phase.TICK,
                                timer.index() + 1, 5);
                    } else {
                        timers.scheduleNext(timer, LiveEventReplayTimerService.Phase.IDLE, 0, 5);
                    }
                } else {
                    timers.scheduleNext(timer, LiveEventReplayTimerService.Phase.IDLE, 0, 5);
                }
            } catch (RuntimeException failure) {
                log.error("live-event replay failed eventId={} phase={} attempt={}",
                        timer.eventId(), timer.phase(), timer.attempts(), failure);
                timers.retryOrFail(timer, failure, MAX_ATTEMPTS);
            }
        }
    }

    private List<Document> lastTen(List<Document> messages) {
        int start = Math.max(0, messages.size() - 10);
        return messages.subList(start, messages.size());
    }
}
