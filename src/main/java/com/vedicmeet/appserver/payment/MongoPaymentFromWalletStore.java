package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Production {@link PaymentFromWalletStore}. SHADOW-ONLY (only reached via
 * {@link PaymentFromWalletService}, which is unwired).
 */
@Repository
public class MongoPaymentFromWalletStore implements PaymentFromWalletStore {

    private static final Logger log = LoggerFactory.getLogger(MongoPaymentFromWalletStore.class);

    private final MongoTemplate mongo;

    public MongoPaymentFromWalletStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean isUserOnCall(Object userId) {
        Document onCall = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("user_id", String.valueOf(userId)).append("status", "progress")).first();
        return onCall != null;
    }

    @Override
    public double getAdminCommission() {
        Document master = mongo.getCollection(Collections.MASTERS)
                .find(new Document()).projection(new Document("payment", 1).append("_id", 0)).first();
        if (master != null && master.get("payment") instanceof Document) {
            Object commission = ((Document) master.get("payment")).get("commission");
            if (commission instanceof Number) return ((Number) commission).doubleValue();
        }
        return 40; // Node default
    }

    @Override
    public void insertGiftComment(Object broadcastId, Object userId, Object giftId, String comment) {
        mongo.getCollection(Collections.COMMENTS).insertOne(new Document("broadcastId", broadcastId)
                .append("userId", userId).append("type", "1").append("giftId", giftId).append("comment", comment));
    }

    @Override
    public void maybeUpdateWaitlist(Object userId) {
        // Node: reads user.lastBroadcastJoin then updateWaitlistOfUser(...). Call-lifecycle concern (Prompt E).
        log.debug("maybeUpdateWaitlist seam (noop) userId={}", userId);
    }
}
