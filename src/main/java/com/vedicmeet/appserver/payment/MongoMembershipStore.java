package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Production {@link MembershipStore}, reproducing Node's EXACT queries (utils/classes/transaction.js
 * purchaseMembership L736). SHADOW-ONLY (only reached via {@link MembershipPurchaseService}).
 *
 * FAITHFUL: {@link #findWalletCoinsCipher} / {@link #getSavedAmount} / {@link #setSavedAmount} query
 * the {@code waitlists} collection with {@code { userId }} exactly as Node does. Note the waitlist
 * collection keys on {@code user_id} (not {@code userId}), so these typically match nothing (coins→0);
 * preserved intentionally per the team decision — do NOT "fix" this.
 */
@Repository
public class MongoMembershipStore implements MembershipStore {

    private final MongoTemplate mongo;
    private final PushNotificationService push;

    public MongoMembershipStore(MongoTemplate mongo, PushNotificationService push) {
        this.mongo = mongo;
        this.push = push;
    }

    @Override
    public Document findActiveMembership(String membershipId) {
        return mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                .find(new Document("_id", new ObjectId(membershipId)).append("status", true)).first();
    }

    @Override
    public String findWalletCoinsCipher(Object userId) {
        Document w = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("userId", userId)).projection(new Document("coins", 1)).first();
        if (w == null) return null;
        Object coins = w.get("coins");
        return coins == null ? null : String.valueOf(coins);
    }

    @Override
    public double getSavedAmount(Object userId) {
        Document w = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("userId", userId)).projection(new Document("savedAmount", 1)).first();
        if (w == null || !(w.get("savedAmount") instanceof Number)) return 0;
        return ((Number) w.get("savedAmount")).doubleValue();
    }

    @Override
    public void setSavedAmount(Object userId, double savedAmount) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(
                new Document("userId", userId), new Document("$set", new Document("savedAmount", savedAmount)));
    }

    @Override
    public void sendMembershipNotification(Document user, String membershipPlanType) {
        // Node: sendNotificationOnMembership(user, membershipPlanType) — FCM seam.
        push.sendNotificationAndCons("user", null, "Membership activated: " + membershipPlanType, "MEMBERSHIP");
    }
}
