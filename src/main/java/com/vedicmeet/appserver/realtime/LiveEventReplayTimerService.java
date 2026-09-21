package com.vedicmeet.appserver.realtime;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Durable Java replacement for Node live-event-timer-queue BullMQ jobs. */
@Service
public class LiveEventReplayTimerService {

    public enum Phase { IDLE, TICK }

    public record Claimed(String eventId, Phase phase, int index, String generation, int attempts) {}

    private final MongoTemplate mongo;

    public LiveEventReplayTimerService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** A new message invalidates any currently-running replay loop and starts a fresh 10s idle wait. */
    public void scheduleInactivity(String eventId) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("EVENT_ID_REQUIRED");
        Date now = new Date();
        mongo.getCollection(Collections.JAVA_LIVE_EVENT_TIMERS).updateOne(
                new Document("_id", eventId),
                new Document("$set", new Document("phase", Phase.IDLE.name()).append("index", 0)
                        .append("generation", UUID.randomUUID().toString())
                        .append("dueAt", Date.from(Instant.now().plusSeconds(10)))
                        .append("status", "PENDING").append("attempts", 0)
                        .append("owner", "java").append("updatedAt", now))
                        .append("$setOnInsert", new Document("createdAt", now)),
                new UpdateOptions().upsert(true));
    }

    public Claimed claimNextDue() {
        Date now = new Date();
        Document claimed = mongo.getCollection(Collections.JAVA_LIVE_EVENT_TIMERS).findOneAndUpdate(
                new Document("owner", "java").append("status", "PENDING")
                        .append("dueAt", new Document("$lte", now)),
                new Document("$set", new Document("status", "RUNNING")
                        .append("claimedAt", now).append("updatedAt", now))
                        .append("$inc", new Document("attempts", 1)),
                new FindOneAndUpdateOptions().sort(new Document("dueAt", 1))
                        .returnDocument(ReturnDocument.AFTER));
        return claimed == null ? null : new Claimed(claimed.getString("_id"),
                Phase.valueOf(claimed.getString("phase")), number(claimed.get("index")),
                claimed.getString("generation"), number(claimed.get("attempts")));
    }

    /** Reschedule only if no new message has superseded this generation. */
    public boolean scheduleNext(Claimed claimed, Phase phase, int index, long delaySeconds) {
        var result = mongo.getCollection(Collections.JAVA_LIVE_EVENT_TIMERS).updateOne(
                sameGeneration(claimed).append("status", "RUNNING"),
                new Document("$set", new Document("phase", phase.name()).append("index", index)
                        .append("dueAt", Date.from(Instant.now().plusSeconds(Math.max(0, delaySeconds))))
                        .append("status", "PENDING").append("updatedAt", new Date())));
        return result.getModifiedCount() == 1;
    }

    public void markCompleted(Claimed claimed) {
        mongo.getCollection(Collections.JAVA_LIVE_EVENT_TIMERS).updateOne(
                sameGeneration(claimed).append("status", "RUNNING"),
                new Document("$set", new Document("status", "COMPLETED")
                        .append("completedAt", new Date()).append("updatedAt", new Date())));
    }

    public void retryOrFail(Claimed claimed, RuntimeException failure, int maxAttempts) {
        boolean retry = claimed.attempts() < maxAttempts;
        Document set = new Document("status", retry ? "PENDING" : "FAILED")
                .append("lastError", safe(failure)).append("updatedAt", new Date());
        if (retry) set.append("dueAt", Date.from(Instant.now().plusSeconds(
                Math.min(30, 1L << Math.min(claimed.attempts(), 5)))));
        mongo.getCollection(Collections.JAVA_LIVE_EVENT_TIMERS).updateOne(
                sameGeneration(claimed).append("status", "RUNNING"), new Document("$set", set));
    }

    private Document sameGeneration(Claimed value) {
        return new Document("_id", value.eventId()).append("generation", value.generation());
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    private String safe(Throwable error) {
        String value = error == null || error.getMessage() == null
                ? "unknown" : error.getMessage();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
