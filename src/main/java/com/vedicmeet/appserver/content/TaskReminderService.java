package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Durable Java replacement for Node {@code node-schedule} task/remedy notifications. */
@Service
public class TaskReminderService {
    private final MongoTemplate mongo;

    public TaskReminderService(MongoTemplate mongo) { this.mongo = mongo; }

    public List<String> schedule(ObjectId ownerId, ObjectId sourceId, List<String> dates,
                                 List<Document> timings, String earlyReminder,
                                 List<String> tokens, String userType, String reminderType,
                                 String title) {
        List<String> ids = new ArrayList<>();
        Date now = new Date();
        long earlyMillis = earlyMillis(earlyReminder);
        for (String date : dates == null ? List.<String>of() : dates) {
            LocalDate day = parseDay(date);
            for (Document timing : timings == null ? List.<Document>of() : timings) {
                LocalTime time = parseTime(timing.getString("time"));
                // Node constructs UTC and subtracts IST offset before node-schedule.
                Instant due = day.atTime(time).toInstant(ZoneOffset.UTC)
                        .minusSeconds(5 * 3600L + 30 * 60L).minusMillis(earlyMillis);
                if (!due.isAfter(Instant.now())) continue;
                ObjectId id = new ObjectId();
                mongo.getCollection(Collections.JAVA_TASK_REMINDERS).insertOne(new Document("_id", id)
                        .append("ownerId", ownerId).append("sourceId", sourceId)
                        .append("tokens", tokens == null ? List.of() : tokens)
                        .append("userType", userType).append("reminderType", reminderType)
                        .append("title", title).append("dueAt", Date.from(due))
                        .append("status", "PENDING").append("attempts", 0)
                        .append("createdAt", now).append("updatedAt", now));
                ids.add(id.toHexString());
            }
        }
        return ids;
    }

    public void cancel(ObjectId sourceId) {
        mongo.getCollection(Collections.JAVA_TASK_REMINDERS).updateMany(
                new Document("sourceId", sourceId).append("status", new Document("$in",
                        List.of("PENDING", "PROCESSING"))),
                new Document("$set", new Document("status", "CANCELLED").append("updatedAt", new Date())));
    }

    public Document claimNextDue() {
        return mongo.getCollection(Collections.JAVA_TASK_REMINDERS).findOneAndUpdate(
                new Document("status", "PENDING").append("dueAt", new Document("$lte", new Date())),
                new Document("$set", new Document("status", "PROCESSING").append("updatedAt", new Date()))
                        .append("$inc", new Document("attempts", 1)),
                new FindOneAndUpdateOptions().sort(new Document("dueAt", 1))
                        .returnDocument(ReturnDocument.AFTER));
    }

    public void complete(ObjectId id) {
        updateStatus(id, "COMPLETED", null);
    }

    public void retryOrFail(Document job, RuntimeException error, int maxAttempts) {
        Number attempts = job.get("attempts", Number.class);
        boolean failed = attempts != null && attempts.intValue() >= maxAttempts;
        Document set = new Document("status", failed ? "FAILED" : "PENDING")
                .append("updatedAt", new Date()).append("lastError", safe(error.getMessage()));
        if (!failed) set.append("dueAt", new Date(System.currentTimeMillis() + 30_000L));
        mongo.getCollection(Collections.JAVA_TASK_REMINDERS).updateOne(
                new Document("_id", job.getObjectId("_id")), new Document("$set", set));
    }

    private void updateStatus(ObjectId id, String status, String error) {
        Document set = new Document("status", status).append("updatedAt", new Date());
        if (error != null) set.append("lastError", error);
        mongo.getCollection(Collections.JAVA_TASK_REMINDERS).updateOne(
                new Document("_id", id), new Document("$set", set));
    }

    private LocalDate parseDay(String value) {
        try { return OffsetDateTime.parse(value).toLocalDate(); }
        catch (DateTimeParseException ignored) { }
        try { return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate(); }
        catch (DateTimeParseException ignored) { }
        return LocalDate.parse(value.substring(0, Math.min(value.length(), 10)));
    }

    private LocalTime parseTime(String value) {
        if (value == null) throw new IllegalArgumentException("timings is required");
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH))) {
            try { return LocalTime.parse(value.trim().toUpperCase(Locale.ENGLISH), formatter); }
            catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("INVALID_TIME");
    }

    private long earlyMillis(String type) {
        if (type == null || type.isBlank() || "0".equals(type) || "1".equals(type)
                || "never".equalsIgnoreCase(type)) return 0;
        return switch (type) {
            case "2" -> 300_000L;
            case "3" -> 600_000L;
            case "4" -> 900_000L;
            case "5" -> 3_600_000L;
            case "6" -> 7_200_000L;
            case "7" -> 86_400_000L;
            case "8" -> 172_800_000L;
            case "9" -> 604_800_000L;
            default -> 0;
        };
    }

    private String safe(String value) {
        if (value == null) return "UNKNOWN";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
