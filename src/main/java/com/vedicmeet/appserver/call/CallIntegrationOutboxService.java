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
import java.util.List;

/**
 * Durable, Java-owned replacement for the disabled Node call-completion Bull queue.
 * Claims are atomic, stale RUNNING jobs are recovered, and each business step is recorded so a
 * retry cannot repeat already-finished work during an ordinary provider outage.
 */
@Service
public class CallIntegrationOutboxService {

    public record ClaimedJob(String id, String type, Document payload, int attempts,
                             List<String> completedSteps, Document raw) {}

    private final MongoTemplate mongo;

    public CallIntegrationOutboxService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Idempotently enqueue a Java-owned integration job in the caller's Mongo transaction. */
    public void enqueue(String id, String type, Document payload) {
        Date now = new Date();
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", id),
                new Document("$setOnInsert", new Document("type", type)
                        .append("payload", payload == null ? new Document() : payload)
                        .append("status", "PENDING").append("owner", "java")
                        .append("attempts", 0).append("completedSteps", List.of())
                        .append("nextAttemptAt", now).append("createdAt", now)
                        .append("updatedAt", now)),
                new UpdateOptions().upsert(true));
    }

    public ClaimedJob claimNextDue() {
        Date now = new Date();
        Date stale = Date.from(Instant.now().minusSeconds(5 * 60));
        // List.of rejects null at runtime. Use an explicit OR so legacy records with no owner
        // remain claimable without constructing a null-hostile Java collection.
        Document owner = new Document("$or", List.of(
                new Document("owner", "java"),
                new Document("owner", new Document("$exists", false)),
                new Document("owner", null)));
        Document ready = new Document("$or", List.of(
                new Document("status", new Document("$in", List.of("PENDING", "RETRY")))
                        .append("$or", List.of(
                                new Document("nextAttemptAt", new Document("$lte", now)),
                                new Document("nextAttemptAt", new Document("$exists", false)))),
                new Document("status", "RUNNING").append("claimedAt", new Document("$lte", stale))));
        Document claimed = mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX)
                .findOneAndUpdate(new Document("$and", List.of(owner, ready)),
                        new Document("$set", new Document("status", "RUNNING")
                                .append("claimedAt", now).append("updatedAt", now))
                                .append("$inc", new Document("attempts", 1)),
                        new FindOneAndUpdateOptions().sort(new Document("nextAttemptAt", 1)
                                        .append("createdAt", 1))
                                .returnDocument(ReturnDocument.AFTER));
        return claimed == null ? null : toJob(claimed);
    }

    public boolean stepCompleted(ClaimedJob job, String step) {
        return job.completedSteps() != null && job.completedSteps().contains(step);
    }

    public void markStep(String jobId, String step) {
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", jobId).append("status", "RUNNING"),
                new Document("$addToSet", new Document("completedSteps", step))
                        .append("$set", new Document("updatedAt", new Date())));
    }

    public void updateWork(String jobId, String field, Object value) {
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", jobId).append("status", "RUNNING"),
                new Document("$set", new Document("work." + field, value)
                        .append("updatedAt", new Date())));
    }

    public void markCompleted(String jobId) {
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", jobId).append("status", "RUNNING"),
                new Document("$set", new Document("status", "COMPLETED")
                        .append("completedAt", new Date()).append("updatedAt", new Date())));
    }

    public void retryOrFail(ClaimedJob job, RuntimeException failure, int maxAttempts) {
        boolean retry = job.attempts() < maxAttempts;
        long delay = Math.min(300, 1L << Math.min(job.attempts(), 8));
        Document set = new Document("status", retry ? "RETRY" : "FAILED")
                .append("lastError", safeMessage(failure)).append("updatedAt", new Date());
        if (retry) set.append("nextAttemptAt", Date.from(Instant.now().plusSeconds(delay)));
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", job.id()).append("status", "RUNNING"),
                new Document("$set", set));
    }

    @SuppressWarnings("unchecked")
    private ClaimedJob toJob(Document value) {
        Object steps = value.get("completedSteps");
        List<String> completed = steps instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return new ClaimedJob(value.getString("_id"), value.getString("type"),
                value.get("payload") instanceof Document d ? d : new Document(),
                number(value.get("attempts")), completed, value);
    }

    private int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
