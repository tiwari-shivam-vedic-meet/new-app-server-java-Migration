package com.vedicmeet.appserver.realtime;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Mongo persistence for the production /live_event namespace. */
@Repository
public class LiveEventStore {

    private final MongoTemplate mongo;

    public LiveEventStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public List<Document> events() {
        return mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS)
                .find().into(new ArrayList<>());
    }

    public Document event(String eventId) {
        return findById(Collections.CONSULTANT_LIVE_EVENTS, eventId);
    }

    public Document user(String userId) {
        if (!ObjectId.isValid(userId)) return null;
        return mongo.getCollection(Collections.USERS).find(new Document("_id", new ObjectId(userId))
                .append("isDeleted", false).append("status", true)).first();
    }

    public Document consultant(String consultantId) {
        if (!ObjectId.isValid(consultantId)) return null;
        return mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", new ObjectId(consultantId))
                .append("isDeleted", new Document("$ne", true)).append("status", true)
                .append("isAdminVerify", true)).first();
    }

    public void setConsultantLive(String consultantId, boolean online) {
        mongo.getCollection(Collections.CONSULTANTS).updateOne(
                new Document("_id", id(consultantId)),
                new Document("$set", new Document("isOnline", online)
                        .append("isLiveOngoing", online).append("updatedAt", new Date())));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document toggleBlock(String eventId, String userId, String consultantId, boolean blocked) {
        Document event = event(eventId);
        if (event == null) throw new IllegalStateException("Event not found");
        if (!String.valueOf(event.get("consultant_id")).equals(consultantId)) {
            throw new IllegalStateException("Not allowed for this event");
        }
        Date now = new Date();
        mongo.getCollection(Collections.USER_CONS_RELS).updateOne(
                new Document("userId", id(userId)).append("consId", id(consultantId)),
                new Document("$set", new Document("forCons.isUserBlocked", blocked)
                        .append("updatedAt", now))
                        .append("$setOnInsert", new Document("createdAt", now)),
                new UpdateOptions().upsert(true));

        Document update = blocked
                ? new Document("$addToSet", new Document("blockedUsers", userId))
                : new Document("$pull", new Document("blockedUsers", userId));
        update.append("$push", new Document("logs", new Document("timestamp", now)
                .append("action", blocked ? "user_blocked" : "user_unblocked")
                .append("userId", userId)))
                .append("$set", new Document("updatedAt", now));
        return mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS).findOneAndUpdate(
                new Document("_id", id(eventId)), update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public List<String> blockedUsers(String eventId) {
        Document event = event(eventId);
        if (event == null) throw new IllegalStateException("Event not found");
        Object value = event.get("blockedUsers");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }

    public boolean isBlocked(String eventId, String userId, String consultantId) {
        if (blockedUsers(eventId).contains(userId)) return true;
        Document rel = mongo.getCollection(Collections.USER_CONS_RELS).find(
                new Document("userId", id(userId)).append("consId", id(consultantId))).first();
        return rel != null && Boolean.TRUE.equals(doc(rel.get("forCons")).get("isUserBlocked"));
    }

    public Document activeUserWaitlist(String userId, String consultantId) {
        return mongo.getCollection(Collections.WAITLISTS).find(new Document("user_id", userId)
                .append("consultant_id", consultantId).append("used_for", "live_event")
                .append("status", new Document("$in", List.of("waiting", "blocked", "progress"))))
                .first();
    }

    public boolean consultantBusy(String consultantId, String eventId, String excludingWaitlistId) {
        Document filter = new Document("consultant_id", consultantId)
                .append("status", new Document("$in", List.of("progress", "initiated")));
        if (excludingWaitlistId != null) filter.append("_id", new Document("$ne", id(excludingWaitlistId)));
        // A normal consultation and a live-event private chat cannot own the same consultant.
        return mongo.getCollection(Collections.WAITLISTS).find(filter).first() != null;
    }

    public Document insertWaitlist(Document waitlist) {
        mongo.getCollection(Collections.WAITLISTS).insertOne(waitlist);
        return waitlist;
    }

    public Document claimProgress(String waitlistId) {
        Date now = new Date();
        return mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", id(waitlistId)).append("used_for", "live_event")
                        .append("status", "waiting"),
                new Document("$set", new Document("status", "progress")
                        .append("timeLap.startTime", now).append("updatedAt", now))
                        .append("$push", new Document("logs", new Document("callStatus", "started")
                                .append("timestamp", now.getTime()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document claimNext(String consultantId, String eventId) {
        Date now = new Date();
        return mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("consultant_id", consultantId).append("session_info.eventId", eventId)
                        .append("used_for", "live_event").append("status", "waiting"),
                new Document("$set", new Document("status", "progress")
                        .append("timeLap.startTime", now).append("updatedAt", now))
                        .append("$push", new Document("logs", new Document("callStatus", "started")
                                .append("timestamp", now.getTime()))),
                new FindOneAndUpdateOptions().sort(new Document("priority", -1).append("createdAt", -1))
                        .returnDocument(ReturnDocument.AFTER));
    }

    public Document nextWaiting(String consultantId, String eventId) {
        return mongo.getCollection(Collections.WAITLISTS).find(
                        new Document("consultant_id", consultantId)
                                .append("session_info.eventId", eventId)
                                .append("used_for", "live_event").append("status", "waiting"))
                .sort(new Document("priority", -1).append("createdAt", -1)).first();
    }

    public void revertProgress(String waitlistId, String reason) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(
                new Document("_id", id(waitlistId)).append("used_for", "live_event")
                        .append("status", "progress"),
                new Document("$set", new Document("status", "waiting")
                        .append("updatedAt", new Date()))
                        .append("$push", new Document("logs", new Document("callStatus", reason)
                                .append("timestamp", System.currentTimeMillis()))));
    }

    /** Exact aggregate response shape used by Node getLiveEventWaitlist. */
    public List<Document> waitlist(String consultantId, String eventId) {
        List<Document> entries = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("consultant_id", consultantId)
                        .append("session_info.eventId", eventId).append("status", "waiting")
                        .append("used_for", "live_event"))
                .sort(new Document("priority", -1).append("createdAt", -1))
                .into(new ArrayList<>());
        if (entries.isEmpty()) return List.of();
        double total = entries.stream().mapToDouble(entry -> number(entry.get("requested_time"))).sum();
        return List.of(new Document("_id", null).append("totalRequestedTime", total)
                .append("entries", entries));
    }

    private Document findById(String collection, String raw) {
        if (raw == null || raw.isBlank()) return null;
        return mongo.getCollection(collection).find(new Document("_id", id(raw))).first();
    }

    private Object id(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
