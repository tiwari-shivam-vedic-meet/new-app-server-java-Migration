package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** Mobile feedback write from Node feedback-service.js. */
@Service
public class FeedbackService {
    private final MongoTemplate mongo;
    private final PushNotificationService push;
    public FeedbackService(MongoTemplate mongo, PushNotificationService push) {
        this.mongo = mongo; this.push = push;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void add(Map<String, Object> input, Document actor, String userType) {
        Object rating = input == null ? null : input.get("rating");
        String review = input == null || input.get("review") == null ? "" : String.valueOf(input.get("review"));
        if (!(rating instanceof Number number) || number.doubleValue() % 1 != 0)
            throw new IllegalArgumentException("rating is required and must be an integer");
        if (review.isBlank()) throw new IllegalArgumentException("review is required");

        Date now = new Date();
        Document feedback = new Document(input).append("userId", "user".equals(userType) ? actor.get("_id") : null)
                .append("consultantId", "cons".equals(userType) ? actor.get("_id") : null)
                .append("userType", userType).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.NOTIFICATIONS).insertOne(
                new Document("userType", "admin").append("senderType", "system")
                        .append("message", "You Receive a new feedBack")
                        .append("createdAt", now).append("updatedAt", now));
        mongo.getCollection(Collections.FEEDBACKS).insertOne(feedback);

        if ("user".equals(userType)) {
            String name = actor.getString("name");
            for (String token : tokens(actor)) {
                push.sendNotificationAndCons("user", token,
                        (name == null ? "null" : name) + " Your review is send Successfully.",
                        Map.of(), "Vedic Meet", "user");
            }
        }
    }

    private List<String> tokens(Document actor) {
        Object raw = actor.get("device") instanceof Document d ? d.get("fcmToken") : null;
        return raw instanceof List<?> values ? values.stream().filter(v -> v != null)
                .map(String::valueOf).filter(v -> !v.isBlank()).toList() : List.of();
    }
}
