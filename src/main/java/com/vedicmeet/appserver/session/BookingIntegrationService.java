package com.vedicmeet.appserver.session;

import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.call.CallLifecycleService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable post-booking integration work; each DB side effect is idempotent by job id. */
@Service
public class BookingIntegrationService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final CallLifecycleService calls;
    private final PushNotificationService push;
    private final ChatServerClient chat;

    public BookingIntegrationService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                     CallLifecycleService calls, PushNotificationService push,
                                     ChatServerClient chat) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.calls = calls;
        this.push = push;
        this.chat = chat;
    }

    public void initiate(CallIntegrationOutboxService.ClaimedJob job) {
        Document p = job.payload();
        calls.initiateCall(required(p, "userId"), required(p, "consultantId"),
                required(p, "waitlistId"), required(p, "callMode"));
    }

    public void postBooking(CallIntegrationOutboxService.ClaimedJob job) {
        Document p = job.payload();
        Document user = require(Collections.USERS, p.get("userId"), "USER_NOT_FOUND");
        Document consultant = require(Collections.CONSULTANTS, p.get("consultantId"), "CONSULTANT_NOT_FOUND");
        String waitlistId = required(p, "waitlistId");
        boolean scheduled = Boolean.TRUE.equals(p.get("scheduled"));

        if (scheduled) {
            step(job, "user-notification", () -> notifyActor(job.id() + ":user", user, "user",
                    "Vedic Meet", "Your session is allotted. We will let you know once the consultant accepts it.",
                    Map.of("screen", "order-history")));
            step(job, "consultant-notification", () -> notifyActor(job.id() + ":consultant", consultant,
                    "cons", "New Session Booked", display(user, "User") + " booked a session with you.",
                    Map.of("screen", "waitlist", "userName", display(user, "User"), "waitlistId", waitlistId)));
        } else {
            step(job, "consultant-notification", () -> notifyActor(job.id() + ":consultant", consultant,
                    "cons", "New User in Waitlist", display(user, "User") + " has been added to your waitlist.",
                    Map.of("screen", "waitlist", "userName", display(user, "User"))));
        }
        step(job, "traffic-source", () -> trackTraffic(job.id(), doc(p.get("trafficSource"))));
    }

    public void feedChatForm(CallIntegrationOutboxService.ClaimedJob job) {
        Document p = job.payload();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", required(p, "threadId"));
        payload.put("form", doc(p.get("form")));
        Map<String, Object> response = chat.feedFirstMessageForm(payload);
        if (!Boolean.TRUE.equals(response.get("success"))) throw new IllegalStateException("CHAT_FORM_REJECTED");
    }

    private void notifyActor(String id, Document actor, String userType, String title,
                             String body, Map<String, Object> data) {
        for (String token : tokens(actor)) {
            push.sendNotificationAndCons(userType, token, body, data, title, userType);
        }
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATION_RECORDS).updateOne(new Document("_id", id),
                new Document("$setOnInsert", new Document("receiverId", objectId(actor.get("_id")))
                        .append("message", body).append("title", title).append("userType", userType)
                        .append("senderType", "system").append("type", "other").append("data", data)
                        .append("isRead", false).append("status", true)
                        .append("createdAt", now).append("updatedAt", now)),
                new UpdateOptions().upsert(true));
    }

    private void trackTraffic(String jobId, Document source) {
        String mode = source.getString("mode");
        Object exploreId = source.get("exploreId");
        if (exploreId == null || (!"chat".equals(mode) && !"audio".equals(mode))) return;
        String field = "chat".equals(mode) ? "stats.totalConsultantBookingChat"
                : "stats.totalConsultantBookingAudio";
        mongo.getCollection(Collections.EXPLORES).updateOne(
                new Document("_id", objectId(exploreId)).append("javaBookingTrafficEvents", new Document("$ne", jobId)),
                new Document("$inc", new Document(field, 1))
                        .append("$addToSet", new Document("javaBookingTrafficEvents", jobId)));
    }

    private void step(CallIntegrationOutboxService.ClaimedJob job, String name, Runnable action) {
        if (outbox.stepCompleted(job, name)) return;
        action.run();
        outbox.markStep(job.id(), name);
    }

    private Document require(String collection, Object id, String error) {
        Document value = mongo.getCollection(collection).find(new Document("_id", objectId(id))).first();
        if (value == null) throw new IllegalStateException(error);
        return value;
    }

    private String required(Document doc, String key) {
        String value = text(doc.get(key));
        if (value.isBlank()) throw new IllegalStateException("BOOKING_JOB_MISSING_" + key.toUpperCase());
        return value;
    }

    private String display(Document actor, String fallback) {
        String value = text(actor.get("name"));
        if (value.isBlank()) value = text(actor.get("accountName"));
        return value.isBlank() ? fallback : value;
    }

    private List<String> tokens(Document actor) {
        Object value = doc(actor.get("device")).get("fcmToken");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !text(item).isBlank()).map(String::valueOf).toList();
    }

    private Object objectId(Object value) {
        if (value instanceof ObjectId) return value;
        String text = text(value);
        return ObjectId.isValid(text) ? new ObjectId(text) : value;
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
