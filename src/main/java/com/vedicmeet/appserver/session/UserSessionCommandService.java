package com.vedicmeet.appserver.session;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.LiveKitProvider;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Remaining mutating commands from production user/session.js. */
@Service
public class UserSessionCommandService {

    public static class DailyQueryLimitException extends RuntimeException {
        public DailyQueryLimitException() {
            super("You have already submitted a query today. You can submit again tomorrow.");
        }
    }

    public record QuickQueryResult(String id, String dayKey, String status) {}

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final LiveKitProvider liveKit;
    private final MediaUploadService media;

    public UserSessionCommandService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                     LiveKitProvider liveKit, MediaUploadService media) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.liveKit = liveKit;
        this.media = media;
    }

    public void setConsultantHistoryAccess(Document actor, Map<String, Object> input) {
        String waitlistId = requiredText(input, "waitlistId");
        if (!ObjectId.isValid(waitlistId)) throw new IllegalArgumentException("Waitlist not found");
        Document changed = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                ownedWaitlist(actor, waitlistId),
                new Document("$set", new Document("session_info.canConsultantAccessHistory",
                        Boolean.TRUE.equals(input.get("canConsultantAccessHistory")))
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (changed == null) throw new IllegalArgumentException("Waitlist not found");
    }

    /**
     * Atomically records a one-time extension and queues the Redis billing-clock update. Keeping the
     * timer side effect in the durable outbox prevents a successful DB response from losing the timer
     * update when Redis is momentarily unavailable.
     */
    @Transactional(transactionManager = "mongoTransactionManager")
    public String rechargeStatus(Document actor, Map<String, Object> input) {
        if (!"extend".equals(text(input == null ? null : input.get("type")))) {
            throw new IllegalArgumentException("Invalid recharge status type");
        }
        String waitlistId = requiredText(input, "waitlistId");
        if (!ObjectId.isValid(waitlistId)) throw new IllegalArgumentException("Waitlist not found");
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(ownedWaitlist(actor, waitlistId)).first();
        if (waitlist == null) throw new IllegalArgumentException("Waitlist not found");

        Document user = mongo.getCollection(Collections.USERS)
                .find(new Document("_id", id(requiredActor(actor)))).projection(new Document("wallet", 1)).first();
        Document consultant = findById(Collections.CONSULTANTS, waitlist.get("consultant_id"));
        double wallet = number(user == null ? null : user.get("wallet"));
        Document session = doc(waitlist.get("session_info"));
        double hold = number(session.get("holdAmount"));
        double price = number(doc(consultant == null ? null : consultant.get("price")).get("default"));
        double amountLeft = wallet - hold;
        int minutes = price > 0 && amountLeft > 0 ? (int) Math.floor(amountLeft / price) : 0;
        if (minutes <= 0 || Boolean.TRUE.equals(session.get("isSessionExtended"))) return "recharge_required";

        // Preserve Node's response for a valid non-progress row: no mutation, but successful_extended.
        if (!"progress".equals(waitlist.getString("status"))) return "successfull_extended";

        long seconds = minutes * 60L;
        Document updated = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                ownedWaitlist(actor, waitlistId).append("status", "progress")
                        .append("session_info.isSessionExtended", new Document("$ne", true)),
                new Document("$inc", new Document("requested_time", seconds)
                                .append("session_info.holdAmount", amountLeft))
                        .append("$set", new Document("session_info.isSessionExtended", true)
                                .append("updatedAt", new Date()))
                        .append("$push", new Document("logs", new Document("callStatus", "session_extended")
                                .append("actionBy", "user").append("actionValue", seconds)
                                .append("wallet", wallet).append("amountLeft", amountLeft)
                                .append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) return "recharge_required";
        outbox.enqueue("CALL_TIMER_EXTEND:" + waitlistId, "CALL_TIMER_EXTEND",
                new Document("waitlistId", waitlistId).append("additionalSeconds", seconds));
        return "successfull_extended";
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public QuickQueryResult createQuickQuery(Document actor, Map<String, Object> input) {
        Object actorId = requiredActor(actor);
        String query = text(input == null ? null : input.get("queryText")).trim();
        if (query.isBlank()) throw new IllegalArgumentException("Query text is required");
        if (query.length() > 2000) query = query.substring(0, 2000);
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String userId = text(actorId);

        Document existing = mongo.getCollection(Collections.WAITLISTS).find(new Document("user_id", userId)
                .append("session_info.dayKey", day).append("used_for", "query"))
                .projection(new Document("_id", 1)).first();
        if (existing != null) throw new DailyQueryLimitException();

        String claimId = "QUICK_QUERY:" + userId + ":" + day;
        String token = UUID.randomUUID().toString();
        Document claim = mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).findOneAndUpdate(
                new Document("_id", claimId),
                new Document("$setOnInsert", new Document("token", token).append("status", "PENDING")
                        .append("owner", "java-quick-query").append("createdAt", new Date())
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (claim == null || !token.equals(claim.getString("token"))) throw new DailyQueryLimitException();

        Document form = doc(input == null ? null : input.get("requestForm"));
        form.put("concern", query);
        Date now = new Date();
        Document waitlist = new Document("_id", new ObjectId()).append("user_id", userId)
                .append("consultant_id", null).append("requested_time", 0).append("request_form", form)
                .append("coupon", null).append("session_info", new Document("price", 0)
                        .append("platformShare", 0).append("mode", "chat")
                        .append("canConsultantAccessHistory", true).append("messageLimit", 3)
                        .append("isSessionExtended", false).append("holdAmount", 0)
                        .append("bookType", "DEFAULT").append("isTriedAgainAfterMissed", false)
                        .append("isBoosted", false).append("dayKey", day))
                .append("used_for", "query").append("status", "completed")
                .append("onCompletion", new Document("baseAmount", 0).append("amountToDeduct", 0)
                        .append("platformAmount", 0).append("consultantAmount", 0)
                        .append("callDurationInSeconds", 0).append("isAmountRefunded", false))
                .append("logs", new ArrayList<>()).append("priority", 1).append("threadId", null)
                .append("orderId", null).append("deviceUsedToken", null).append("trafficSource", null)
                .append("createdAt", now).append("updatedAt", now)
                .append("timeLap", new Document("startTime", now).append("endTime", now));
        mongo.getCollection(Collections.WAITLISTS).insertOne(waitlist);
        mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).updateOne(
                new Document("_id", claimId).append("token", token),
                new Document("$set", new Document("status", "COMPLETED")
                        .append("resultId", waitlist.get("_id")).append("updatedAt", new Date())));

        String name = text(actor.get("name"));
        if (name.isBlank()) name = text(doc(actor.get("details")).get("firstName"));
        if (name.isBlank()) name = "User";
        outbox.enqueue("USER_QUERY_CREATED:" + waitlist.get("_id"), "USER_QUERY_CREATED",
                new Document("waitlistId", text(waitlist.get("_id"))).append("userId", userId)
                        .append("userName", name).append("queryText", query));
        return new QuickQueryResult(text(waitlist.get("_id")), day, "pending");
    }

    public Document uploadPlaystoreScreenshot(Document actor, String type, MultipartFile file) {
        Object actorId = requiredActor(actor);
        String normalizedType = type == null ? "" : type;
        Document filter = new Document("userId", id(actorId)).append("type", normalizedType);
        if (mongo.getCollection(Collections.PLAYSTORE_REVIEW_UPLOADS).find(filter).first() != null) {
            throw new IllegalStateException("Screenshot already uploaded");
        }

        String claimId = "PLAYSTORE_SCREENSHOT:" + text(actorId) + ":" + normalizedType;
        String token = UUID.randomUUID().toString();
        Document claim = mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).findOneAndUpdate(
                new Document("_id", claimId),
                new Document("$setOnInsert", new Document("token", token).append("status", "PENDING")
                        .append("owner", "java-playstore-upload").append("createdAt", new Date())),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (claim == null || !token.equals(claim.getString("token"))) {
            throw new IllegalStateException("Screenshot already uploaded");
        }
        try {
            String key = media.upload(file, "playstore-screenshot");
            Document saved = new Document("_id", new ObjectId()).append("screenshotUrl", key)
                    .append("type", normalizedType).append("userId", id(actorId))
                    .append("isGranted", false).append("createdAt", new Date())
                    .append("updatedAt", new Date());
            mongo.getCollection(Collections.PLAYSTORE_REVIEW_UPLOADS).insertOne(saved);
            mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).updateOne(
                    new Document("_id", claimId).append("token", token),
                    new Document("$set", new Document("status", "COMPLETED")
                            .append("resultId", saved.get("_id")).append("updatedAt", new Date())));
            return saved;
        } catch (RuntimeException failure) {
            mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS)
                    .deleteOne(new Document("_id", claimId).append("token", token));
            throw failure;
        }
    }

    public Map<String, Object> generateLiveKitToken(Document actor, Map<String, Object> input) {
        Object actorId = requiredActor(actor);
        String waitlistId = text(input == null ? null : input.get("waitlistId"));
        if (!waitlistId.isBlank()) {
            if (!ObjectId.isValid(waitlistId)
                    || mongo.getCollection(Collections.WAITLISTS).find(ownedWaitlist(actor, waitlistId)).first() == null) {
                throw new IllegalArgumentException("Waitlist not found");
            }
        }
        String room = text(input == null ? null : input.get("roomName"));
        if (room.isBlank()) room = "room_" + ThreadLocalRandom.current().nextInt(1, 1_000_001);
        String participant = text(input == null ? null : input.get("participantName"));
        if (participant.isBlank()) participant = "user_" + System.currentTimeMillis() + "_"
                + ThreadLocalRandom.current().nextInt(1000);
        boolean canPublish = input == null || !Boolean.FALSE.equals(input.get("canPublish"));
        return liveKit.participantToken(room, participant, canPublish);
    }

    public boolean liveKitReady() { return liveKit.isReady(); }

    private Document ownedWaitlist(Document actor, String waitlistId) {
        return new Document("_id", new ObjectId(waitlistId))
                .append("user_id", new Document("$in", idVariants(requiredActor(actor))));
    }
    private Document findById(String collection, Object raw) {
        if (raw == null) return null;
        return mongo.getCollection(collection)
                .find(new Document("_id", new Document("$in", idVariants(raw)))).first();
    }
    private List<Object> idVariants(Object raw) {
        String value = text(raw);
        if (raw instanceof ObjectId) return List.of(raw, value);
        return ObjectId.isValid(value) ? List.of(raw, new ObjectId(value)) : List.of(raw);
    }
    private Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String value = text(raw);
        return ObjectId.isValid(value) ? new ObjectId(value) : raw;
    }
    private Object requiredActor(Document actor) {
        if (actor == null || actor.get("_id") == null) throw new IllegalStateException("Unauthorized");
        return actor.get("_id");
    }
    private String requiredText(Map<String, Object> source, String key) {
        String value = text(source == null ? null : source.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    @SuppressWarnings("unchecked")
    private Document doc(Object value) {
        return value instanceof Document d ? new Document(d)
                : value instanceof Map<?, ?> map ? new Document((Map<String, Object>) map) : new Document();
    }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
