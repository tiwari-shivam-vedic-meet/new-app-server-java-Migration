package com.vedicmeet.appserver.payment;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** Database seam for Node CouponV2Service.recordRechargeWithCoupon. */
public interface RechargeCouponRewardStore {

    Document findLatestSavedPayment(Object userId);

    Document findActiveCoupon(String normalizedCode);

    Document findActiveInfluencer(Object influencerId);

    boolean rewardAlreadyRecorded(Object transactionId);

    void record(Reward reward);

    record Reward(Object userId, Object transactionId, Object couponId, String couponCode,
                  Object influencerId, double rechargeBaseAmount, double gstAmount,
                  double totalPaid, double discountPercent, double discountAmount,
                  double extraCoins, double finalAmountPaid,
                  double influencerSharePercent, double influencerEarning) {}

    @Repository
    class Mongo implements RechargeCouponRewardStore {
        private final MongoTemplate mongo;
        private final WalletService walletService;

        public Mongo(MongoTemplate mongo, WalletService walletService) {
            this.mongo = mongo;
            this.walletService = walletService;
        }

        @Override
        public Document findLatestSavedPayment(Object userId) {
            return mongo.getCollection(Collections.SAVE_INITIAL_PAYMENTS)
                    .find(new Document("userId", userId))
                    .sort(new Document("createdAt", -1)).first();
        }

        @Override
        public Document findActiveCoupon(String normalizedCode) {
            return mongo.getCollection(Collections.V2_COUPONS)
                    .find(new Document("code", normalizedCode).append("isActive", true)).first();
        }

        @Override
        public Document findActiveInfluencer(Object influencerId) {
            return mongo.getCollection(Collections.INFLUENCERS)
                    .find(new Document("_id", influencerId).append("isActive", true)).first();
        }

        @Override
        public boolean rewardAlreadyRecorded(Object transactionId) {
            return mongo.getCollection(Collections.RECHARGE_TRANSACTION_LOGS)
                    .find(new Document("transactionId", transactionId))
                    .projection(new Document("_id", 1)).first() != null;
        }

        /**
         * All five effects join the payment Mongo transaction. Unlike the old best-effort chain,
         * an infrastructure failure cannot leave bonus coins without its audit/ledger rows.
         */
        @Override
        @Transactional(transactionManager = "mongoTransactionManager")
        public void record(Reward r) {
            if (rewardAlreadyRecorded(r.transactionId())) return;

            Date now = new Date();
            ObjectId rechargeLogId = new ObjectId();
            Document rechargeLog = new Document("_id", rechargeLogId)
                    .append("userId", r.userId())
                    .append("transactionId", r.transactionId())
                    .append("rechargeBaseAmount", r.rechargeBaseAmount())
                    .append("gstAmount", r.gstAmount())
                    .append("totalPaid", r.totalPaid())
                    .append("couponId", r.couponId())
                    .append("couponCode", r.couponCode())
                    .append("discountPercent", r.discountPercent())
                    .append("discountAmount", r.discountAmount())
                    .append("extraCoins", r.extraCoins())
                    .append("finalAmountPaid", r.finalAmountPaid())
                    .append("influencerId", r.influencerId())
                    .append("influencerSharePercent", r.influencerSharePercent())
                    .append("influencerEarning", r.influencerEarning())
                    .append("status", "success")
                    .append("createdAt", now).append("updatedAt", now);
            mongo.getCollection(Collections.RECHARGE_TRANSACTION_LOGS).insertOne(rechargeLog);

            mongo.getCollection(Collections.INFLUENCER_LEDGERS).insertOne(
                    new Document("_id", new ObjectId())
                            .append("influencerId", r.influencerId())
                            .append("rechargeLogId", rechargeLogId)
                            .append("userId", r.userId())
                            .append("type", "EARNING")
                            .append("amount", r.influencerEarning())
                            .append("rechargeBaseAmount", r.rechargeBaseAmount())
                            .append("couponCode", r.couponCode())
                            .append("status", "pending")
                            .append("createdAt", now).append("updatedAt", now));

            mongo.getCollection(Collections.V2_COUPONS).updateOne(
                    new Document("_id", r.couponId()),
                    new Document("$inc", new Document("usageCount", 1)));

            mongo.getCollection(Collections.USER_COUPON_STATES).findOneAndUpdate(
                    new Document("userId", r.userId()).append("couponCode", r.couponCode()),
                    new Document("$inc", new Document("timesUsed", 1))
                            .append("$set", new Document("lastUsedAt", now)
                                    .append("isActive", false)
                                    .append("couponId", r.couponId())
                                    .append("type", "INFLUENCER")
                                    .append("couponCode", r.couponCode())
                                    .append("updatedAt", now))
                            .append("$setOnInsert", new Document("createdAt", now)),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

            if (r.extraCoins() > 0) {
                walletService.creditAndDebitOnWallet("user", "credit",
                        new Document("userId", r.userId())
                                .append("coins", 0)
                                .append("extraCoins", r.extraCoins())
                                .append("transactionFor", "coupon_bonus")
                                .append("walletDeductReason", "Bonus coins from coupon " + r.couponCode()));
            }
        }
    }
}
