package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.session.SessionBookingStore;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixed-session and quick-query assignment workflows. Provider calls happen outside Mongo
 * transactions; short-lived durable claims stop two consultants/Node instances assigning the same job.
 */
@Service
public class ConsultantSessionAssignmentService {

    public record AssignmentResult(boolean success, String code, String message, Object data) {}

    private final MongoTemplate mongo;
    private final ChatServerClient chat;
    private final SessionBookingStore bookings;
    private final CallIntegrationOutboxService outbox;
    private final TransactionTemplate transactions;

    public ConsultantSessionAssignmentService(MongoTemplate mongo, ChatServerClient chat,
                                              SessionBookingStore bookings,
                                              CallIntegrationOutboxService outbox,
                                              MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.chat = chat;
        this.bookings = bookings;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AssignmentResult acceptFixedSession(Document actor, String waitlistId) {
        String consultantId = requiredActor(actor);
        Object fixedId = validatedId(waitlistId);
        String token = UUID.randomUUID().toString();
        Date now = new Date();
        Document fixed = mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS).findOneAndUpdate(
                new Document("_id", fixedId).append("consultant_id", null)
                        .append("status", "waiting").append("for", "fixed_session"),
                new Document("$set", new Document("consultant_id", actor.get("_id"))
                        .append("javaMigrationClaim", new Document("token", token)
                                .append("kind", "fixed_session").append("claimedAt", now))
                        .append("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (fixed == null) {
            return new AssignmentResult(false, "SESSION_ALREADY_ACCEPTED_BY_ANOTHER_CONSULTANT",
                    "Session already accepted by another consultant", null);
        }

        try {
            Document user = require(Collections.USERS, fixed.get("user_id"), "User not found!");
            Map<String, Object> threadPayload = new LinkedHashMap<>();
            threadPayload.put("user", user);
            threadPayload.put("cons", consultantSummary(actor));
            Map<String, Object> thread = chat.createThread(threadPayload);
            String threadId = successfulData(thread, "Failed to create thread!");

            Document copy = copyDocument(fixed.get("waitlistCopy"));
            Document forms = new Document("one", copy.get("request_form"))
                    .append("two", copy.get("request_form1"))
                    .append("three", copy.get("request_form2"));
            Map<String, Object> feed = chat.feedFirstMessageFormForSessions(
                    Map.of("threadId", threadId, "forms", forms));
            if (!Boolean.TRUE.equals(feed.get("success"))) {
                throw new IllegalStateException("Failed to feed session forms!");
            }

            String orderId = bookings.nextOrderId();
            Document created = transactions.execute(status -> finalizeFixed(
                    fixedId, token, fixed, copy, actor, threadId, orderId));
            if (created == null) throw new IllegalStateException("Fixed session claim was lost");
            Document data = new Document("userId", fixed.get("user_id"))
                    .append("consultantId", actor.get("_id"))
                    .append("roomId", created.get("_id"));
            return new AssignmentResult(true, "SESSION_ACCEPTED_SUCCESSFULLY",
                    "Session accepted successfully", data);
        } catch (RuntimeException failure) {
            rollbackFixed(fixedId, token);
            throw failure;
        }
    }

    public AssignmentResult acceptQuery(Document actor, String waitlistId) {
        String consultantId = requiredActor(actor);
        Object queryId = validatedId(waitlistId);
        Document existing = mongo.getCollection(Collections.WAITLISTS).find(
                new Document("_id", queryId).append("used_for", "query").append("status", "completed"))
                .first();
        if (existing == null) return new AssignmentResult(false, "QUERY_NOT_FOUND", "Query not found.", null);

        String currentOwner = text(existing.get("consultant_id"));
        if (!currentOwner.isBlank()) return alreadyAccepted(existing, consultantId, currentOwner);

        String token = UUID.randomUUID().toString();
        Date now = new Date();
        Document claimed = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", queryId).append("used_for", "query").append("status", "completed")
                        .append("$or", List.of(new Document("consultant_id", null),
                                new Document("consultant_id", ""))),
                new Document("$set", new Document("consultant_id", consultantId)
                        .append("javaMigrationClaim", new Document("token", token)
                                .append("kind", "query").append("claimedAt", now))
                        .append("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (claimed == null) {
            Document latest = mongo.getCollection(Collections.WAITLISTS).find(new Document("_id", queryId)).first();
            return latest == null ? new AssignmentResult(false, "QUERY_NOT_FOUND", "Query not found.", null)
                    : alreadyAccepted(latest, consultantId, text(latest.get("consultant_id")));
        }

        try {
            Document user = require(Collections.USERS, claimed.get("user_id"), "User not found!");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", user);
            payload.put("cons", consultantSummary(actor));
            payload.put("requestForm", claimed.get("request_form"));
            String threadId = successfulData(chat.createThreadForQuery(payload), "Failed to create thread!");
            String orderId = bookings.nextOrderId();

            String userName = text(user.get("name"));
            if (userName.isBlank()) userName = "User";
            String consultantName = displayName(actor);
            Document from = new Document("id", consultantId)
                    .append("profileImage", actor.get("profileImage")).append("name", consultantName);
            Document message = new Document("threadId", threadId).append("from", from)
                    .append("content", "Hello " + userName + ", I am " + consultantName
                            + " and I am here to help you with your query.")
                    .append("type", "message");
            Map<String, Object> sent = chat.sendMessageUsingRestApi(Map.of("message", message));
            if (sent.get("success") != null && !Boolean.TRUE.equals(sent.get("success"))) {
                throw new IllegalStateException("Failed to send query greeting!");
            }

            Document completed = transactions.execute(status -> finalizeQuery(
                    queryId, token, claimed, actor, threadId, orderId, consultantName));
            if (completed == null) throw new IllegalStateException("Query claim was lost");
            Document data = new Document("id", text(completed.get("_id")))
                    .append("consultant_id", consultantId).append("waitlist", completed);
            return new AssignmentResult(true, null, "Query accepted.", data);
        } catch (RuntimeException failure) {
            rollbackQuery(queryId, token, consultantId);
            throw failure;
        }
    }

    private Document finalizeFixed(Object fixedId, String token, Document fixed, Document copy,
                                   Document actor, String threadId, String orderId) {
        Document claimFilter = new Document("_id", fixedId)
                .append("javaMigrationClaim.token", token)
                .append("consultant_id", actor.get("_id"));
        if (mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS).find(claimFilter).first() == null) return null;

        copy.remove("_id");
        copy.remove("id");
        ObjectId newId = new ObjectId();
        copy.put("_id", newId);
        copy.put("orderId", orderId);
        copy.put("threadId", threadId);
        copy.put("consultant_id", text(actor.get("_id")));
        Date now = new Date();
        copy.putIfAbsent("createdAt", now);
        copy.put("updatedAt", now);

        Document sessionInfo = copyDocument(copy.get("session_info"));
        Document price = copyDocument(actor.get("price"));
        sessionInfo.put("platformShare", price.get("platformShare"));
        Document meta = copyDocument(sessionInfo.get("sessionMeta"));
        List<Object> forms = new ArrayList<>();
        addIfPresent(forms, copy.get("request_form"));
        addIfPresent(forms, copy.get("request_form1"));
        addIfPresent(forms, copy.get("request_form2"));
        meta.put("forms", forms);
        sessionInfo.put("sessionMeta", meta);
        copy.put("session_info", sessionInfo);

        mongo.getCollection(Collections.WAITLISTS).insertOne(copy);
        long matched = mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS).updateOne(claimFilter,
                new Document("$set", new Document("waitlist_id", newId)
                        .append("consultant_id", actor.get("_id")).append("updatedAt", now))
                        .append("$unset", new Document("javaMigrationClaim", ""))).getMatchedCount();
        if (matched != 1) throw new IllegalStateException("Fixed session claim was lost");
        return copy;
    }

    private Document finalizeQuery(Object queryId, String token, Document claimed, Document actor,
                                   String threadId, String orderId, String consultantName) {
        Date now = new Date();
        Document updated = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", queryId).append("javaMigrationClaim.token", token)
                        .append("consultant_id", text(actor.get("_id"))),
                new Document("$set", new Document("threadId", threadId).append("orderId", orderId)
                        .append("updatedAt", now))
                        .append("$unset", new Document("javaMigrationClaim", ""))
                        .append("$push", new Document("logs", new Document("callStatus",
                                "query accepted by consultant").append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) return null;
        String waitlistId = text(updated.get("_id"));
        outbox.enqueue("CONSULTANT_QUERY_ACCEPTED:" + waitlistId, "CONSULTANT_QUERY_ACCEPTED",
                new Document("waitlistId", waitlistId)
                        .append("userId", text(claimed.get("user_id")))
                        .append("consultantId", text(actor.get("_id")))
                        .append("consultantName", consultantName).append("threadId", threadId));
        return updated;
    }

    private AssignmentResult alreadyAccepted(Document waitlist, String actorId, String ownerId) {
        if (actorId.equals(ownerId)) {
            return new AssignmentResult(true, "ALREADY_ACCEPTED_BY_YOU",
                    "You already accepted this query.", new Document("waitlist", waitlist));
        }
        Document owner = ownerId == null || ownerId.isBlank() ? null
                : mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", MongoIds.id(ownerId))).first();
        String name = owner == null ? "Another consultant" : displayName(owner);
        Document data = new Document("waitlist", waitlist)
                .append("acceptedByConsultantId", ownerId == null || ownerId.isBlank() ? null : ownerId)
                .append("acceptedByConsultantName", name);
        return new AssignmentResult(false, "ALREADY_ACCEPTED_BY_OTHER",
                "This query has already been accepted by " + name + ".", data);
    }

    private void rollbackFixed(Object id, String token) {
        mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS).updateOne(
                new Document("_id", id).append("javaMigrationClaim.token", token),
                new Document("$set", new Document("consultant_id", null).append("updatedAt", new Date()))
                        .append("$unset", new Document("javaMigrationClaim", "")));
    }

    private void rollbackQuery(Object id, String token, String consultantId) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(
                new Document("_id", id).append("javaMigrationClaim.token", token)
                        .append("consultant_id", consultantId),
                new Document("$set", new Document("consultant_id", null).append("updatedAt", new Date()))
                        .append("$unset", new Document("javaMigrationClaim", "")));
    }

    private Map<String, Object> consultantSummary(Document actor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_id", text(actor.get("_id")));
        result.put("profileImage", actor.get("profileImage"));
        result.put("name", displayName(actor));
        return result;
    }

    private String successfulData(Map<String, Object> response, String error) {
        if (response == null || !Boolean.TRUE.equals(response.get("success")) || response.get("data") == null) {
            throw new IllegalStateException(error);
        }
        return text(response.get("data"));
    }

    private Document require(String collection, Object id, String message) {
        Document result = mongo.getCollection(collection).find(new Document("_id", MongoIds.id(id))).first();
        if (result == null) throw new IllegalStateException(message);
        return result;
    }
    private Object validatedId(String value) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new IllegalArgumentException("Valid waitlistId is required");
        }
        return new ObjectId(value);
    }
    private String requiredActor(Document actor) {
        String id = actor == null ? "" : text(actor.get("_id"));
        if (id.isBlank()) throw new IllegalStateException("Consultant not found");
        return id;
    }
    private String displayName(Document actor) {
        String name = text(actor.get("accountName"));
        return name.isBlank() ? "Another consultant" : name;
    }
    @SuppressWarnings("unchecked")
    private Document copyDocument(Object value) {
        if (value instanceof Document document) return new Document(document);
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }
    private void addIfPresent(List<Object> target, Object value) { if (value != null) target.add(value); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
