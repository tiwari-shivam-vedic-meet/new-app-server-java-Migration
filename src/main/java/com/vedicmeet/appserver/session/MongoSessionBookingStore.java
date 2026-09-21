package com.vedicmeet.appserver.session;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Mongo implementation with Java-only leases and an atomic booking transaction. */
@Repository
public class MongoSessionBookingStore implements SessionBookingStore {

    private static final List<String> ACTIVE_USER_STATUSES =
            List.of("waiting", "blocked", "missed", "progress", "initiated");
    private static final List<String> ACTIVE_QUEUE_STATUSES =
            List.of("waiting", "progress", "initiated");

    private final MongoTemplate mongo;
    private final SessionBookingPricingService pricing;
    private final CallIntegrationOutboxService outbox;

    public MongoSessionBookingStore(MongoTemplate mongo, SessionBookingPricingService pricing,
                                    CallIntegrationOutboxService outbox) {
        this.mongo = mongo;
        this.pricing = pricing;
        this.outbox = outbox;
    }

    @Override
    public Document loadUser(Object userId) {
        return mongo.getCollection(Collections.USERS).find(new Document("_id", id(userId))
                .append("isDeleted", false).append("status", true)).first();
    }

    @Override
    public Document loadConsultant(String consultantId) {
        return mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", id(consultantId))
                .append("isDeleted", new Document("$ne", true)).append("status", true)
                .append("isAdminVerify", true)).first();
    }

    @Override
    public boolean isUserBlocked(Object userId, Object consultantId) {
        Document relation = mongo.getCollection(Collections.USER_CONS_RELS).find(
                new Document("userId", id(userId)).append("consId", id(consultantId))).first();
        return relation != null && Boolean.TRUE.equals(doc(relation.get("forCons")).get("isUserBlocked"));
    }

    @Override
    public boolean hasActiveWaitlist(String userId) {
        return mongo.getCollection(Collections.WAITLISTS).find(new Document("user_id", userId)
                .append("status", new Document("$in", ACTIVE_USER_STATUSES))).first() != null;
    }

    @Override
    public void updateFcmTokens(Object userId, List<String> tokens) {
        mongo.getCollection(Collections.USERS).updateOne(new Document("_id", id(userId)),
                new Document("$set", new Document("device.fcmToken", tokens)));
    }

    @Override
    public String nextOrderId() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        Instant start = now.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = now.withDayOfMonth(1).plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long serial = mongo.getCollection(Collections.WAITLISTS).countDocuments(
                new Document("createdAt", new Document("$gte", Date.from(start))
                        .append("$lt", Date.from(end)))) + 1;
        return "VM" + now.format(DateTimeFormatter.ofPattern("yyMMMdd", Locale.US)).toUpperCase(Locale.US)
                + serial;
    }

    @Override
    public Lease acquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Date now = new Date();
        Date expires = Date.from(Instant.now().plus(ttl));
        Document claim = new Document("_id", key).append("token", token)
                .append("expiresAt", expires).append("createdAt", now).append("updatedAt", now);
        try {
            mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).insertOne(claim);
            return new Lease(key, token);
        } catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCode() != 11000) throw duplicate;
            Document reclaimed = mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).findOneAndUpdate(
                    new Document("_id", key).append("expiresAt", new Document("$lte", now)),
                    new Document("$set", new Document("token", token).append("expiresAt", expires)
                            .append("updatedAt", now)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return reclaimed == null ? null : new Lease(key, token);
        }
    }

    @Override
    public void release(Lease lease) {
        if (lease != null) mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS)
                .deleteOne(new Document("_id", lease.key()).append("token", lease.token()));
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public PersistResult persist(Document waitlist, SessionBookingPricingService.Quote quote,
                                 ScheduledCharge scheduledCharge, boolean modeLive,
                                 String userName, String consultantName) {
        String userId = waitlist.getString("user_id");
        String consultantId = waitlist.getString("consultant_id");
        if (hasActiveWaitlist(userId)) throw new IllegalStateException("You are already in waitlist!");

        long queueBefore = mongo.getCollection(Collections.WAITLISTS).countDocuments(
                new Document("consultant_id", consultantId)
                        .append("status", new Document("$in", ACTIVE_QUEUE_STATUSES)));

        if (scheduledCharge != null) reserveScheduledSession(waitlist, scheduledCharge, consultantName);

        pricing.consume(quote);
        mongo.getCollection(Collections.WAITLISTS).insertOne(waitlist);

        boolean initiate = scheduledCharge == null && queueBefore == 0 && modeLive;
        if (initiate) {
            Document session = doc(waitlist.get("session_info"));
            outbox.enqueue("CALL_INITIATE:" + waitlist.get("_id"), "CALL_INITIATE",
                    new Document("waitlistId", text(waitlist.get("_id")))
                            .append("userId", userId).append("consultantId", consultantId)
                            .append("callMode", session.getString("requestedMode") == null
                                    ? session.getString("mode") : session.getString("requestedMode")));
        }
        outbox.enqueue("BOOKING_POST:" + waitlist.get("_id"), "BOOKING_POST_PROCESSING",
                new Document("waitlistId", text(waitlist.get("_id"))).append("userId", userId)
                        .append("consultantId", consultantId).append("userName", userName)
                        .append("consultantName", consultantName)
                        .append("scheduled", scheduledCharge != null)
                        .append("trafficSource", waitlist.get("trafficSource")));

        Document response = new Document(waitlist);
        response.append("isConsultantJustReadyToConnect", initiate);
        return new PersistResult(response, queueBefore, initiate);
    }

    @Override
    public void enqueueChatFormRetry(String waitlistId, String threadId, Document form) {
        outbox.enqueue("BOOKING_CHAT_FORM:" + waitlistId, "BOOKING_CHAT_FORM",
                new Document("waitlistId", waitlistId).append("threadId", threadId).append("form", form));
    }

    private void reserveScheduledSession(Document waitlist, ScheduledCharge charge, String consultantName) {
        Object consultantObjectId = id(waitlist.get("consultant_id"));
        Document slotFilter = new Document("consultant_id", new Document("$in",
                List.of(consultantObjectId, text(waitlist.get("consultant_id")))))
                .append("slotDay", charge.slotDay()).append("slotTime", charge.slotTime())
                .append("isActive", true);
        if (mongo.getCollection(Collections.CONSULTANT_SLOTS_BOOKS).find(slotFilter).first() != null) {
            throw new IllegalStateException("Consultant has no slots available for this day and time!");
        }

        Object userObjectId = id(waitlist.get("user_id"));
        Document user = mongo.getCollection(Collections.USERS).findOneAndUpdate(
                new Document("_id", userObjectId).append("wallet", new Document("$gte", charge.amount())),
                new Document("$inc", new Document("wallet", -charge.amount())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (user == null) {
            throw new IllegalStateException("Insufficient balance! Required balance for "
                    + charge.minutes() + " minutes session is " + charge.amount() + " coins");
        }

        Date now = new Date();
        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(
                new Document("userId", userObjectId).append("userType", "user")
                        .append("transactionFor", "session_book").append("coins", charge.amount())
                        .append("transactionType", 1).append("isConsTransfer", false)
                        .append("startDate", now).append("endDate", now).append("planDuration", 1)
                        .append("discountPercentage", charge.discountPercentage())
                        .append("meta", new Document("sessionId", null)
                                .append("orderId", waitlist.get("orderId"))
                                .append("name", consultantName)
                                .append("mode", "session-book")
                                .append("callDuration", charge.minutes()))
                        .append("createdAt", now).append("updatedAt", now));

        mongo.getCollection(Collections.CONSULTANT_SLOTS_BOOKS).insertOne(
                new Document("consultant_id", consultantObjectId).append("waitlist_id", waitlist.get("_id"))
                        .append("user_id", userObjectId).append("slotDay", charge.slotDay())
                        .append("slotTime", charge.slotTime()).append("isActive", true)
                        .append("createdAt", now).append("updatedAt", now));
    }

    private Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String value = text(raw);
        return ObjectId.isValid(value) ? new ObjectId(value) : raw;
    }
    private static Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
