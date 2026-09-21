package com.vedicmeet.appserver.payment;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.MetaConversionsClient;
import com.vedicmeet.appserver.payment.PaymentReconciliationService.ReconStatus;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Production {@link ReconciliationStore}. Mirrors the Node worker's Mongo ops. Provider status is
 * independently fetched through fail-closed gateway adapters, and successful reconciliation routes
 * through the idempotent, transactional {@link PaymentConfirmService}.
 */
@Repository
public class MongoReconciliationStore implements ReconciliationStore {

    private static final int MAX_RETRIES = 10;
    private static final int BATCH_SIZE = 50;

    private final MongoTemplate mongo;
    private final PaymentConfirmService confirmService;
    private final PhonePeGatewayClient phonePe;
    private final PaytmGatewayClient paytm;
    private final MetaConversionsClient meta;

    public MongoReconciliationStore(
            MongoTemplate mongo,
            PaymentConfirmService confirmService,
            PhonePeGatewayClient phonePe,
            PaytmGatewayClient paytm,
            MetaConversionsClient meta) {
        this.mongo = mongo;
        this.confirmService = confirmService;
        this.phonePe = phonePe;
        this.paytm = paytm;
        this.meta = meta;
    }

    @Override
    public List<Document> getPendingPayments() {
        Document query = new Document("status", "INITIATED")
                .append("metaEventFired", new Document("$ne", true))
                .append("metaEventRetryCount", new Document("$lt", MAX_RETRIES))
                .append("$or", Arrays.asList(
                        new Document("paymentGateway", "phonepe"),
                        new Document("paymentGateway", "paytm"),
                        new Document("paymentGateway", new Document("$in", Arrays.asList(null, "")))
                                .append("orderId", new Document("$not", new Document("$regex", "^order_")))));
        List<Document> out = new ArrayList<>();
        mongo.getCollection(Collections.TRANSACTIONS).find(query)
                .sort(new Document("createdAt", 1)).limit(BATCH_SIZE).into(out);
        return out;
    }

    @Override
    public void incrementRetryCount(Object transactionId) {
        mongo.getCollection(Collections.TRANSACTIONS).updateOne(idFilter(transactionId),
                new Document("$inc", new Document("metaEventRetryCount", 1)));
    }

    @Override
    public ReconStatus phonePeStatus(String orderId) {
        return phonePe.status(orderId);
    }

    @Override
    public ReconStatus paytmStatus(String orderId) {
        return paytm.status(orderId);
    }

    @Override
    public void markTransactionFailed(Object transactionId) {
        mongo.getCollection(Collections.TRANSACTIONS).updateOne(idFilter(transactionId),
                new Document("$set", new Document("status", "FAILED")));
    }

    @Override
    public void confirmPayment(String orderId, String userId, String paymentId) {
        confirmService.confirmPayment(orderId, paymentId != null ? paymentId : orderId, false);
    }

    @Override
    public boolean isMetaEventFired(Object transactionId) {
        Document tx = mongo.getCollection(Collections.TRANSACTIONS).find(idFilter(transactionId)).first();
        return tx != null && Boolean.TRUE.equals(tx.get("metaEventFired"));
    }

    @Override
    public boolean claimMetaEvent(Object transactionId) {
        Document updated = mongo.getCollection(Collections.TRANSACTIONS).findOneAndUpdate(
                idFilter(transactionId).append("metaEventFired", new Document("$ne", true)),
                new Document("$set", new Document("metaEventFired", true).append("metaEventFiredAt", new java.util.Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return updated != null;
    }

    @Override
    public void fireMetaPurchaseEvent(Document transaction) {
        meta.purchase(transaction, userIdentifier(transaction.get("userId")));
    }

    @Override
    public boolean isMetaPublisherReady() {
        return meta.isReady();
    }

    private String userIdentifier(Object userId) {
        Document user = mongo.getCollection(Collections.USERS)
                .find(idFilter(userId)).projection(new Document("details.phone", 1)
                        .append("details.email", 1).append("email", 1)).first();
        if (user == null) return userId == null ? null : String.valueOf(userId);
        Document details = user.get("details") instanceof Document value ? value : new Document();
        Object phone = details.get("phone");
        if (phone != null && !String.valueOf(phone).isBlank()) return String.valueOf(phone);
        Object email = details.get("email") == null ? user.get("email") : details.get("email");
        return email == null ? String.valueOf(userId) : String.valueOf(email);
    }

    private Document idFilter(Object transactionId) {
        Object id = transactionId;
        if (transactionId instanceof String && ObjectId.isValid((String) transactionId)) {
            id = new ObjectId((String) transactionId);
        }
        return new Document("_id", id);
    }
}
