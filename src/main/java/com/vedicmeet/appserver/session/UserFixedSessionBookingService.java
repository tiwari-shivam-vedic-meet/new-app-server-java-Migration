package com.vedicmeet.appserver.session;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Atomic wallet + ledger + fixed-session port of Node POST /book-fixed-session. */
@Service
public class UserFixedSessionBookingService {

    private final MongoTemplate mongo;
    private final FixedSessionBookingFactory factory;
    private final CallIntegrationOutboxService outbox;

    public UserFixedSessionBookingService(MongoTemplate mongo, FixedSessionBookingFactory factory,
                                          CallIntegrationOutboxService outbox) {
        this.mongo = mongo;
        this.factory = factory;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document book(Document authenticatedUser, Map<String, Object> request) {
        FixedSessionBookingFactory.Prepared prepared = factory.prepare(authenticatedUser, request);
        String token = UUID.randomUUID().toString();
        Date now = new Date();
        Document claim = mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).findOneAndUpdate(
                new Document("_id", prepared.claimId()),
                new Document("$setOnInsert", new Document("token", token).append("status", "PENDING")
                        .append("owner", "java-fixed-session").append("createdAt", now)
                        .append("updatedAt", now)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (claim == null) throw new IllegalStateException("Unable to reserve fixed session");
        if (!token.equals(claim.getString("token"))) {
            Document existing = existingResult(claim);
            if (existing != null) return existing;
            throw new IllegalStateException("Your fixed-session booking is already being processed. Please wait.");
        }

        Object userId = authenticatedUser.get("_id");
        Document updatedUser = mongo.getCollection(Collections.USERS).findOneAndUpdate(
                new Document("_id", id(userId)).append("isDeleted", false).append("status", true)
                        .append("wallet", new Document("$gte", prepared.price())),
                new Document("$inc", new Document("wallet", -prepared.price()))
                        .append("$set", new Document("device.fcmToken", prepared.fcmTokens())
                                .append("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updatedUser == null) {
            throw new IllegalStateException("Insufficient balance! Minimum balance required is "
                    + prepared.price() + " coins");
        }

        Document fixed = prepared.fixedSession();
        mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS).insertOne(fixed);
        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(new Document("_id", new ObjectId())
                .append("userId", id(userId)).append("userType", "user")
                .append("transactionFor", "session_book").append("coins", prepared.price())
                .append("transactionType", 1).append("isConsTransfer", false)
                .append("meta", new Document("sessionId", null).append("orderId", null)
                        .append("name", null).append("mode", "chat")
                        .append("callDuration", doc(fixed.get("category")).get("consultationTimeInMinutes")))
                .append("createdAt", now).append("updatedAt", now));

        outbox.enqueue("FIXED_SESSION_PUSH:" + fixed.get("_id"), "APP_PUSH_NOTIFICATION",
                new Document("targetType", "user").append("targetId", String.valueOf(userId))
                        .append("title", "Vedic Meet")
                        .append("body", "Your session is allotted. We will let you know once the consultant accepts it.")
                        .append("data", new Document("screen", "order-history")));
        mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).updateOne(
                new Document("_id", prepared.claimId()).append("token", token),
                new Document("$set", new Document("status", "COMPLETED")
                        .append("resultId", fixed.get("_id")).append("updatedAt", new Date())));
        return fixed;
    }

    private Document existingResult(Document claim) {
        Object resultId = claim.get("resultId");
        if (resultId == null) return null;
        return mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS)
                .find(new Document("_id", id(resultId))).first();
    }

    private Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String value = String.valueOf(raw);
        return ObjectId.isValid(value) ? new ObjectId(value) : raw;
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
}
