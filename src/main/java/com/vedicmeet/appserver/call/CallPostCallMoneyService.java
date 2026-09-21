package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** Atomically applies the special post-call coin return and its ledger row exactly once. */
@Service
public class CallPostCallMoneyService {

    static final String STEP = "special-session-reward";
    private final MongoTemplate mongo;

    public CallPostCallMoneyService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public double apply(CallIntegrationOutboxService.ClaimedJob job) {
        Document claim = mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).find(
                new Document("_id", job.id()).append("status", "RUNNING")
                        .append("completedSteps", new Document("$ne", STEP))).first();
        if (claim == null) return reward(job.payload());

        Document payload = job.payload();
        double reward = reward(payload);
        String sessionType = text(payload.get("sessionType"));
        if (isRewardSession(sessionType)) {
            Object userId = id(payload.get("userId"));
            mongo.getCollection(Collections.USERS).updateOne(new Document("_id", userId),
                    new Document("$inc", new Document("wallet", reward)));
            Date now = new Date();
            mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(
                    new Document("userId", userId).append("userType", "user")
                            .append("transactionFor", "wallet_add").append("coins", reward)
                            .append("transactionType", 0).append("isConsTransfer", false)
                            .append("meta", new Document("sessionId", payload.get("waitlistId"))
                                    .append("callDuration", number(payload.get("callDurationSeconds")) / 60.0))
                            .append("createdAt", now).append("updatedAt", now));
        }
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", job.id()).append("status", "RUNNING")
                        .append("completedSteps", new Document("$ne", STEP)),
                new Document("$addToSet", new Document("completedSteps", STEP))
                        .append("$set", new Document("updatedAt", new Date())));
        return reward;
    }

    static double reward(Document payload) {
        String type = text(payload.get("sessionType"));
        double seconds = number(payload.get("callDurationSeconds"));
        if ("CHAT_AUDIO_LIMITLESS".equals(type)) return 5;
        if ("5COINSPERMIN_99AUDIOENDLESS".equals(type)) {
            return "99FORINFINITE".equals(text(payload.get("bookType")))
                    ? 10 : Math.floor(seconds / 60.0);
        }
        if ("NORMAL".equals(type)) return Math.floor(seconds / 60.0);
        return 0;
    }

    static boolean isRewardSession(String type) {
        return "CHAT_AUDIO_LIMITLESS".equals(type)
                || "5COINSPERMIN_99AUDIOENDLESS".equals(type)
                || "NORMAL".equals(type);
    }

    private static Object id(Object value) {
        if (value instanceof ObjectId) return value;
        String text = text(value);
        return ObjectId.isValid(text) ? new ObjectId(text) : value;
    }

    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
