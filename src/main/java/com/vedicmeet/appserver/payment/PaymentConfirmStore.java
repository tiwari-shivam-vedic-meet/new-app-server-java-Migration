package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Port seam for {@link PaymentConfirmService}. The Mongo impl mirrors Node's transaction-model ops and
 * routes the credit through the already-tested {@link WalletService} ({@code creditAndDebitOnWallet}).
 */
public interface PaymentConfirmStore {

    Document findTransactionByOrderId(String orderId);

    /** transactionModel.findOne({userId, status:{$in:[COMPLETED,paid]}}).countDocuments(). */
    long countPreviousPaid(Object userId);

    /** Atomic findOneAndUpdate({orderId, status:'INITIATED'}, $set COMPLETED...), {new:true}; null if not INITIATED. */
    Document atomicComplete(String orderId, String paymentId, boolean metaEventFired, boolean eventQueuedFired);

    /** creditAndDebitOnWallet('user','credit', input) with coins/transactionId/paymentId/userId from the tx. */
    void creditUserForConfirm(Document updatedTx, String paymentId);

    void handleRechargeCouponRewards(Document updatedTx);

    @Repository
    class Mongo implements PaymentConfirmStore {
        private final MongoTemplate mongo;
        private final WalletService walletService;
        private final RechargeCouponRewardService couponRewards;

        public Mongo(MongoTemplate mongo, WalletService walletService,
                     RechargeCouponRewardService couponRewards) {
            this.mongo = mongo;
            this.walletService = walletService;
            this.couponRewards = couponRewards;
        }

        @Override
        public Document findTransactionByOrderId(String orderId) {
            return mongo.getCollection(Collections.TRANSACTIONS).find(new Document("orderId", orderId)).first();
        }

        @Override
        public long countPreviousPaid(Object userId) {
            return mongo.getCollection(Collections.TRANSACTIONS).countDocuments(
                    new Document("userId", userId).append("status",
                            new Document("$in", java.util.Arrays.asList("COMPLETED", "paid"))));
        }

        @Override
        public Document atomicComplete(String orderId, String paymentId, boolean metaEventFired, boolean eventQueuedFired) {
            return mongo.getCollection(Collections.TRANSACTIONS).findOneAndUpdate(
                    new Document("orderId", orderId).append("status", "INITIATED"),
                    new Document("$set", new Document("paymentId", paymentId).append("status", "COMPLETED")
                            .append("metaEventFired", metaEventFired).append("eventQueuedFired", eventQueuedFired)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        }

        @Override
        public void creditUserForConfirm(Document updatedTx, String paymentId) {
            Document input = new Document();
            input.put("coins", updatedTx.get("coins") == null ? 0 : updatedTx.get("coins"));
            input.put("transactionId", updatedTx.get("_id"));
            input.put("paymentId", paymentId);
            input.put("userId", updatedTx.get("userId"));
            walletService.creditAndDebitOnWallet("user", "credit", input);
        }

        @Override
        public void handleRechargeCouponRewards(Document updatedTx) {
            couponRewards.process(updatedTx);
        }
    }
}
