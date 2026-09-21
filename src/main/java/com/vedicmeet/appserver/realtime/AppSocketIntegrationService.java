package com.vedicmeet.appserver.realtime;

import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.InteraktClient;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Executes durable push and Interakt side effects created by {@code /app} socket writes. */
@Service
public class AppSocketIntegrationService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final PushNotificationService push;
    private final InteraktClient interakt;

    public AppSocketIntegrationService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                       PushNotificationService push, InteraktClient interakt) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.push = push;
        this.interakt = interakt;
    }

    public void push(CallIntegrationOutboxService.ClaimedJob job) {
        Document p = job.payload();
        String targetType = required(p, "targetType");
        String targetId = required(p, "targetId");
        String collection = "cons".equals(targetType) ? Collections.CONSULTANTS : Collections.USERS;
        Document actor = mongo.getCollection(collection).find(new Document("_id", id(targetId))).first();
        if (actor == null) throw new IllegalStateException("APP_PUSH_TARGET_NOT_FOUND");
        Document data = doc(p.get("data"));

        if (!outbox.stepCompleted(job, "push")) {
            for (String token : tokens(actor)) {
                push.sendNotificationAndCons(targetType, token, required(p, "body"), data,
                        required(p, "title"), targetType);
            }
            outbox.markStep(job.id(), "push");
        }
        if (!outbox.stepCompleted(job, "notification-record")) {
            Date now = new Date();
            mongo.getCollection(Collections.NOTIFICATION_RECORDS).updateOne(
                    new Document("_id", "SOCKET:" + job.id()),
                    new Document("$setOnInsert", new Document("receiverId", id(targetId))
                            .append("message", p.get("body")).append("title", p.get("title"))
                            .append("userType", targetType).append("senderType", "system")
                            .append("type", "other").append("data", data).append("isRead", false)
                            .append("status", true).append("createdAt", now).append("updatedAt", now)),
                    new UpdateOptions().upsert(true));
            outbox.markStep(job.id(), "notification-record");
        }
    }

    public void interakt(CallIntegrationOutboxService.ClaimedJob job) {
        Document p = job.payload();
        if (outbox.stepCompleted(job, "interakt")) return;
        interakt.queueInteraktEvent(required(p, "eventName"), required(p, "userId"),
                text(p.get("phone")), map(p.get("eventProperties")), map(p.get("traits")));
        outbox.markStep(job.id(), "interakt");
    }

    private List<String> tokens(Document actor) {
        Object value = doc(actor.get("device")).get("fcmToken");
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item != null && !text(item).isBlank()) result.add(text(item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private Object id(Object value) {
        if (value instanceof ObjectId) return value;
        String candidate = text(value);
        return ObjectId.isValid(candidate) ? new ObjectId(candidate) : value;
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String required(Document source, String key) {
        String value = text(source.get(key));
        if (value.isBlank()) throw new IllegalStateException("APP_INTEGRATION_MISSING_" + key.toUpperCase());
        return value;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
