package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallCancelService;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.call.CallLifecycleService;
import com.vedicmeet.appserver.call.CallQueueService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.realtime.SocketEventPublisher;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable side effects of an availability toggle; replaces Node's detached promise chain. */
@Service
public class ConsultantIntegrationService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final CallQueueService queue;
    private final CallLifecycleService lifecycle;
    private final CallCancelService cancel;
    private final PushNotificationService push;
    private final SocketEventPublisher events;

    public ConsultantIntegrationService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                        CallQueueService queue, CallLifecycleService lifecycle,
                                        CallCancelService cancel, PushNotificationService push,
                                        SocketEventPublisher events) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.queue = queue;
        this.lifecycle = lifecycle;
        this.cancel = cancel;
        this.push = push;
        this.events = events;
    }

    public void availabilityChanged(CallIntegrationOutboxService.ClaimedJob job) {
        Document payload = job.payload();
        String consultantId = required(payload, "consultantId");
        boolean online = Boolean.TRUE.equals(payload.get("value"));
        String key = required(payload, "key");
        Document consultant = find(Collections.CONSULTANTS, consultantId);
        if (consultant == null) throw new IllegalStateException("CONSULTANT_NOT_FOUND");

        if (online) {
            step(job, "next-call", () -> initiateNext(consultant));
            step(job, "online-notifications", () -> notifyOnline(consultant, key, job.id()));
        } else {
            step(job, "cancel-rings", () -> cancelRings(consultantId));
            step(job, "offline-notifications", () -> notifyOffline(consultant, job.id()));
        }
    }

    /** Fan-out that Node performs after a consultant activates an offer. */
    public void offerActivated(CallIntegrationOutboxService.ClaimedJob job) {
        String consultantId = required(job.payload(), "consultantId");
        String couponId = required(job.payload(), "couponId");
        Document consultant = find(Collections.CONSULTANTS, consultantId);
        Document coupon = find(Collections.COUPONS, couponId);
        if (consultant == null || coupon == null) throw new IllegalStateException("CONSULTANT_OFFER_NOT_FOUND");
        step(job, "offer-followers", () -> {
            String name = display(consultant);
            String discount = text(coupon.get("couponDiscount"));
            String message = discount + "% off on " + name
                    + ". Hurry up! Offer expires in 60 minutes. Consult now.";
            for (Document follow : mongo.getCollection(Collections.FOLLOWS)
                    .find(new Document("consultantId", objectIdOrString(consultantId)).append("status", true))) {
                String userId = text(follow.get("userId"));
                if (!userId.isBlank()) notifyUser(job.id() + ":offer:" + userId, userId,
                        "Consultant offer", message,
                        new Document("screen", "consultant-details").append("consultantId", consultantId)
                                .append("couponId", couponId));
            }
        });
    }

    /** Follower notification emitted after a consultant creates a live/scheduled event. */
    public void eventCreated(CallIntegrationOutboxService.ClaimedJob job) {
        String consultantId = required(job.payload(), "consultantId");
        String eventId = required(job.payload(), "eventId");
        Document consultant = find(Collections.CONSULTANTS, consultantId);
        Document event = find(Collections.BROADCASTS, eventId);
        if (consultant == null || event == null) throw new IllegalStateException("CONSULTANT_EVENT_NOT_FOUND");
        step(job, "event-followers", () -> {
            String name = display(consultant);
            boolean live = "live".equalsIgnoreCase(text(event.get("eventType")));
            String message = live
                    ? name + " is live now, join the waitlist before more people join."
                    : name + " is going live at " + text(event.get("eventTime"))
                    + ", join the waitlist before more people join.";
            for (Document follow : mongo.getCollection(Collections.FOLLOWS)
                    .find(new Document("consultantId", objectIdOrString(consultantId)).append("status", true))) {
                String userId = text(follow.get("userId"));
                if (!userId.isBlank()) notifyUser(job.id() + ":event:" + userId, userId,
                        "Live event", message,
                        new Document("screen", "LiveEvent").append("eventId", eventId)
                                .append("consultantId", consultantId));
            }
        });
    }

    /** Follower notification emitted after a consultant publishes an Explore post. */
    public void exploreCreated(CallIntegrationOutboxService.ClaimedJob job) {
        String consultantId = required(job.payload(), "consultantId");
        String exploreId = required(job.payload(), "exploreId");
        Document consultant = find(Collections.CONSULTANTS, consultantId);
        Document post = find(Collections.EXPLORES, exploreId);
        if (consultant == null || post == null) throw new IllegalStateException("CONSULTANT_EXPLORE_NOT_FOUND");
        step(job, "explore-followers", () -> {
            String name = display(consultant);
            for (Document relation : mongo.getCollection(Collections.USER_CONS_RELS)
                    .find(new Document("consId", objectIdOrString(consultantId)).append("isUserFollowed", true))) {
                String userId = text(relation.get("userId"));
                if (!userId.isBlank()) notifyUser(job.id() + ":explore:" + userId, userId,
                        name + " shared something specially for you", "Check it out now.",
                        new Document("screen", "Explore").append("exploreId", exploreId));
            }
        });
    }

    /** Durable fan-out after a quick-query claim has a usable chat thread. */
    public void queryAccepted(CallIntegrationOutboxService.ClaimedJob job) {
        Document payload = job.payload();
        String waitlistId = required(payload, "waitlistId");
        String userId = required(payload, "userId");
        String consultantId = required(payload, "consultantId");
        String consultantName = required(payload, "consultantName");
        String threadId = required(payload, "threadId");

        step(job, "consultant-refresh", () -> {
            Document refresh = new Document("waitlistId", waitlistId)
                    .append("acceptedByConsultantId", consultantId)
                    .append("acceptedByConsultantName", consultantName);
            for (Document consultant : mongo.getCollection(Collections.CONSULTANTS).find(
                    new Document("sessionsStatus.isChatLive", true).append("isDeleted", false)
                            .append("status", true).append("_id", new Document("$ne", objectIdOrString(consultantId))))) {
                String receiver = id(consultant);
                events.emitToRoom(receiver, "pending_quick_queries_refresh", refresh);
                for (String token : tokens(consultant)) {
                    push.send("cons", token, "Query Already Accepted",
                            "Accepted by " + consultantName + ".",
                            Map.of("type", "query_dismiss", "queryId", waitlistId,
                                    "acceptedByConsultantId", consultantId,
                                    "acceptedByConsultantName", consultantName),
                            null, false, Map.of());
                }
            }
        });
        step(job, "user-accepted", () -> events.emitToRoom(userId, "quick_query_accepted",
                new Document("waitlistId", waitlistId)
                        .append("acceptedByConsultantId", consultantId)
                        .append("acceptedByConsultantName", consultantName)
                        .append("threadId", threadId).append("roomId", threadId)));
    }

    /** Wallet/refund notifications run after the money transaction commits and are retryable. */
    public void refundNotified(CallIntegrationOutboxService.ClaimedJob job) {
        Document payload = job.payload();
        String userId = required(payload, "userId");
        String consultantId = required(payload, "consultantId");
        double userAmount = number(payload.get("userAmount"));
        double consultantAmount = number(payload.get("consultantAmount"));
        step(job, "user-wallet", () -> notifyActor(job.id() + ":user", Collections.USERS, userId,
                "user", String.format("%.2f Coins have been added to your wallet.", userAmount),
                new Document("screen", "MyWallet").append("coins", userAmount)));
        step(job, "consultant-wallet", () -> notifyActor(job.id() + ":consultant",
                Collections.CONSULTANTS, consultantId, "cons",
                String.format(" %.2f Coins have been deducted from your wallet.", consultantAmount),
                new Document("screen", "MyWallet").append("coins", consultantAmount)));
        step(job, "refund-message", () -> notifyUser(job.id() + ":refund", userId,
                "Refunded", "Your session amount has been refunded", new Document()));
    }

    private void initiateNext(Document consultant) {
        CallQueueService.NextCaller next = queue.sendCallNotificationToNextUser(consultant);
        if (next != null) lifecycle.initiateCall(next.userId, next.consultantId, next.roomId, next.callMode);
    }

    private void cancelRings(String consultantId) {
        Document filter = new Document("consultantId", consultantId)
                .append("isAcceptedByConsultant", false);
        for (Document ring : mongo.getCollection(Collections.CALL_INITIATED).find(filter)) {
            String roomId = text(ring.get("roomId"));
            if (roomId.isBlank()) roomId = text(doc(ring.get("consultantPayload")).get("sessionId"));
            if (roomId.isBlank()) roomId = text(doc(ring.get("userPayload")).get("sessionId"));
            if (!roomId.isBlank()) cancel.cancelCall(roomId, null, consultantId);
        }
    }

    private void notifyOnline(Document consultant, String key, String jobId) {
        String consultantId = id(consultant);
        Set<String> userIds = new LinkedHashSet<>();
        for (Document follow : mongo.getCollection(Collections.FOLLOWS)
                .find(new Document("consultantId", objectIdOrString(consultantId)).append("status", true))) {
            String userId = text(follow.get("userId"));
            if (!userId.isBlank()) userIds.add(userId);
        }
        List<Document> regularUsers = mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("consultant_id", consultantId).append("status", "completed")),
                new Document("$group", new Document("_id", "$user_id").append("completedCount", new Document("$sum", 1))),
                new Document("$match", new Document("completedCount", new Document("$gte", 2)))
        )).into(new ArrayList<>());
        for (Document row : regularUsers) if (!text(row.get("_id")).isBlank()) userIds.add(text(row.get("_id")));

        String name = display(consultant);
        String mode = "isChatLive".equals(key) ? "chat" : "isVoiceLive".equals(key) ? "audio" : "video";
        for (String userId : userIds) {
            notifyUser(jobId + ":online:" + userId, userId,
                    name + " is online",
                    name + " is now available for " + mode + ".",
                    new Document("screen", "consultant-details").append("consultantId", consultantId)
                            .append("callType", mode));
        }
    }

    private void notifyOffline(Document consultant, String jobId) {
        String consultantId = id(consultant);
        Set<String> userIds = new LinkedHashSet<>();
        for (Document waiting : mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("consultant_id", consultantId).append("status", "waiting"))) {
            String userId = text(waiting.get("user_id"));
            if (!userId.isBlank()) userIds.add(userId);
        }
        String name = display(consultant);
        for (String userId : userIds) {
            notifyUser(jobId + ":offline:" + userId, userId, "Consultant is offline!",
                    name + " is now offline. We will notify you when they come back online.",
                    new Document("screen", "consultant-details").append("consultantId", consultantId));
        }
    }

    private void notifyUser(String id, String userId, String title, String body, Document data) {
        Document user = find(Collections.USERS, userId);
        if (user == null) return;
        for (String token : tokens(user)) {
            push.sendNotificationAndCons("user", token, body, data, title, "user");
        }
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATION_RECORDS).updateOne(new Document("_id", id),
                new Document("$setOnInsert", new Document("receiverId", objectIdOrString(userId))
                        .append("message", body).append("title", title).append("userType", "user")
                        .append("senderType", "system").append("type", "other").append("data", data)
                        .append("isRead", false).append("status", true)
                        .append("createdAt", now).append("updatedAt", now)),
                new UpdateOptions().upsert(true));
    }

    private void notifyActor(String notificationId, String collection, String actorId,
                             String userType, String body, Document data) {
        Document actor = find(collection, actorId);
        if (actor == null) return;
        for (String token : tokens(actor)) {
            push.sendNotificationAndCons(userType, token, body, data, "Vedic Meet", userType);
        }
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATION_RECORDS).updateOne(new Document("_id", notificationId),
                new Document("$setOnInsert", new Document("receiverId", objectIdOrString(actorId))
                        .append("message", body).append("title", "Vedic Meet").append("userType", userType)
                        .append("senderType", "system").append("type", "other").append("data", data)
                        .append("isRead", false).append("status", true)
                        .append("createdAt", now).append("updatedAt", now)),
                new UpdateOptions().upsert(true));
    }

    private void step(CallIntegrationOutboxService.ClaimedJob job, String name, Runnable action) {
        if (outbox.stepCompleted(job, name)) return;
        action.run();
        outbox.markStep(job.id(), name);
    }

    private Document find(String collection, String value) {
        return mongo.getCollection(collection).find(new Document("_id", objectIdOrString(value))).first();
    }
    private Object objectIdOrString(String value) {
        return ObjectId.isValid(value) ? new ObjectId(value) : value;
    }
    private List<String> tokens(Document actor) {
        Object value = doc(actor.get("device")).get("fcmToken");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(v -> v != null && !text(v).isBlank()).map(String::valueOf).toList();
    }
    private String display(Document consultant) {
        String value = text(consultant.get("accountName"));
        return value.isBlank() ? "Your consultant" : value;
    }
    private String id(Document actor) {
        Object value = actor.get("_id");
        return value instanceof ObjectId id ? id.toHexString() : text(value);
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String required(Document source, String key) {
        String value = text(source.get(key));
        if (value.isBlank()) throw new IllegalStateException("CONSULTANT_JOB_MISSING_" + key.toUpperCase());
        return value;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
