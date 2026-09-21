package com.vedicmeet.appserver.call;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**
 * Durable Java-owned replacement for the BullMQ delayed call timers.
 *
 * <p>The Redis key remains the user-visible billing clock, exactly as in Node. A companion Mongo
 * record supplies durable callback delivery after a JVM restart. Records are keyed by
 * {@code <type>:<roomId>}, so retrying schedule is idempotent and cannot create duplicate callbacks.</p>
 */
@Service
public class JavaCallTimerService {

    public enum Type { CALL_MISSED, CALL_TIMEOUT, CALL_NEXT_USER }

    public record ClaimedTimer(String id, Type type, String roomId, String userId,
                               String consultantId, String callType, int attempts) {}

    private final MongoTemplate mongo;
    private final TimerWriteService redisClock;

    public JavaCallTimerService(MongoTemplate mongo, TimerWriteService redisClock) {
        this.mongo = mongo;
        this.redisClock = redisClock;
    }

    public void scheduleMissed(String roomId, String userId, String consultantId,
                               String callType, long seconds) {
        schedule(Type.CALL_MISSED, roomId, userId, consultantId, callType,
                "timer:" + roomId, seconds);
    }

    public void scheduleTimeout(String roomId, String userId, String consultantId, long seconds) {
        schedule(Type.CALL_TIMEOUT, roomId, userId, consultantId, "call",
                roomId + ":duration", seconds);
    }

    /** Durable equivalent of Node's 30-second reconnect callback. No Redis billing clock is armed. */
    public void scheduleNextUser(String completedRoomId, String consultantId, long seconds) {
        if (completedRoomId == null || completedRoomId.isBlank()
                || consultantId == null || consultantId.isBlank() || seconds <= 0) {
            throw new IllegalArgumentException("INVALID_CALL_CONTINUATION_TIMER");
        }
        Date now = new Date();
        Document set = new Document("type", Type.CALL_NEXT_USER.name())
                .append("roomId", completedRoomId).append("consultantId", consultantId)
                .append("callType", "continuation").append("dueAt",
                        Date.from(Instant.now().plusSeconds(seconds)))
                .append("status", "PENDING").append("owner", "java")
                .append("attempts", 0).append("updatedAt", now);
        mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                new Document("_id", timerId(Type.CALL_NEXT_USER, completedRoomId)),
                new Document("$set", set).append("$setOnInsert", new Document("createdAt", now)),
                new UpdateOptions().upsert(true));
    }

    private void schedule(Type type, String roomId, String userId, String consultantId,
                          String callType, String redisKey, long seconds) {
        if (roomId == null || roomId.isBlank() || seconds <= 0) {
            throw new IllegalArgumentException("INVALID_CALL_TIMER");
        }
        Date now = new Date();
        Date dueAt = Date.from(Instant.now().plusSeconds(seconds));
        String id = timerId(type, roomId);
        Document set = new Document("type", type.name())
                .append("roomId", roomId)
                .append("userId", userId)
                .append("consultantId", consultantId)
                .append("callType", callType == null ? "call" : callType)
                .append("redisKey", redisKey)
                .append("dueAt", dueAt)
                .append("status", "PENDING")
                .append("owner", "java")
                .append("attempts", 0)
                .append("updatedAt", now);
        mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                new Document("_id", id),
                new Document("$set", set).append("$setOnInsert", new Document("createdAt", now)),
                new UpdateOptions().upsert(true));
        try {
            redisClock.registerTimer(redisKey, seconds);
        } catch (RuntimeException redisFailure) {
            mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                    new Document("_id", id).append("status", "PENDING"),
                    new Document("$set", new Document("status", "FAILED_TO_ARM")
                            .append("lastError", safeMessage(redisFailure)).append("updatedAt", new Date())));
            throw redisFailure;
        }
    }

    public void cancelMissed(String roomId) {
        cancel(Type.CALL_MISSED, roomId, "timer:" + roomId);
    }

    public void cancelTimeout(String roomId) {
        cancel(Type.CALL_TIMEOUT, roomId, roomId + ":duration");
    }

    private void cancel(Type type, String roomId, String redisKey) {
        mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                new Document("_id", timerId(type, roomId))
                        .append("status", new Document("$in", java.util.List.of("PENDING", "RUNNING"))),
                new Document("$set", new Document("status", "CANCELLED").append("updatedAt", new Date())));
        redisClock.stopTimer(redisKey);
    }

    /** Extends both the Redis billing clock and the durable due time. */
    public boolean extendTimeout(String roomId, long additionalSeconds) {
        if (additionalSeconds <= 0) return false;
        String key = roomId + ":duration";
        if (!redisClock.extendTimer(key, additionalSeconds)) return false;
        long remaining = redisClock.getRemainingTime(key);
        if (remaining <= 0) return false;
        mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                new Document("_id", timerId(Type.CALL_TIMEOUT, roomId)).append("status", "PENDING"),
                new Document("$set", new Document("dueAt", Date.from(Instant.now().plusSeconds(remaining)))
                        .append("updatedAt", new Date())));
        return true;
    }

    /** Atomically claims one due callback. Multiple Java workers cannot execute it twice. */
    public ClaimedTimer claimNextDue() {
        Date now = new Date();
        Document claimed = mongo.getCollection(Collections.JAVA_CALL_TIMERS).findOneAndUpdate(
                new Document("owner", "java").append("status", "PENDING")
                        .append("dueAt", new Document("$lte", now)),
                new Document("$set", new Document("status", "RUNNING")
                        .append("claimedAt", now).append("updatedAt", now))
                        .append("$inc", new Document("attempts", 1)),
                new FindOneAndUpdateOptions().sort(new Document("dueAt", 1))
                        .returnDocument(ReturnDocument.AFTER));
        return claimed == null ? null : toTimer(claimed);
    }

    public void markCompleted(String id) {
        mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                new Document("_id", id).append("status", "RUNNING"),
                new Document("$set", new Document("status", "COMPLETED")
                        .append("completedAt", new Date()).append("updatedAt", new Date())));
    }

    public void retryOrFail(ClaimedTimer timer, RuntimeException failure, int maxAttempts) {
        boolean retry = timer.attempts() < maxAttempts;
        Document set = new Document("status", retry ? "PENDING" : "FAILED")
                .append("lastError", safeMessage(failure)).append("updatedAt", new Date());
        if (retry) set.append("dueAt", Date.from(Instant.now().plusSeconds(Math.min(30, 1L << timer.attempts()))));
        mongo.getCollection(Collections.JAVA_CALL_TIMERS).updateOne(
                new Document("_id", timer.id()).append("status", "RUNNING"), new Document("$set", set));
    }

    private ClaimedTimer toTimer(Document d) {
        return new ClaimedTimer(d.getString("_id"), Type.valueOf(d.getString("type")),
                d.getString("roomId"), d.getString("userId"), d.getString("consultantId"),
                d.getString("callType"), number(d.get("attempts")));
    }

    private int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String timerId(Type type, String roomId) {
        return type.name() + ":" + roomId;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
