package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * Production {@link WalletGateway} over MongoTemplate. Each method is a 1:1 mapping of the Node
 * driver call in transaction.js. Deliberately thin (no business logic) — the idempotency logic lives
 * in {@link PaymentWebhookService}, which is what the JUnit suite exercises against a fake gateway.
 *
 * SHADOW-ONLY: this participates only when {@code PaymentWebhookService} is exercised, which is not
 * wired to a live route yet (see the class-level warning there).
 */
@Repository
public class MongoWalletGateway implements WalletGateway {

    private final MongoTemplate mongo;
    private final ObjectMapper mapper;

    public MongoWalletGateway(MongoTemplate mongo, ObjectMapper mapper) {
        this.mongo = mongo;
        this.mapper = mapper;
    }

    @Override
    public void saveWebhookAudit(Map<String, Object> requestData) {
        // Node: requestData.payload = JSON.stringify(requestData.payload); new webhookModel(requestData).save()
        Document doc = new Document(requestData);
        Object payload = doc.get("payload");
        if (payload != null && !(payload instanceof String)) {
            try {
                doc.put("payload", mapper.writeValueAsString(payload));
            } catch (Exception ignore) {
                doc.put("payload", String.valueOf(payload));
            }
        }
        mongo.getCollection(Collections.PAYMENT_WEBHOOKS).insertOne(doc);
    }

    @Override
    public Document findTransactionByOrderId(String orderId) {
        return mongo.getCollection(Collections.TRANSACTIONS)
                .find(new Document("orderId", orderId)).first();
    }

    @Override
    public Document atomicCompleteInitiated(Object transactionId, String paymentId) {
        return mongo.getCollection(Collections.TRANSACTIONS).findOneAndUpdate(
                new Document("_id", transactionId).append("status", "INITIATED"),
                new Document("$set", new Document("paymentId", paymentId).append("status", "COMPLETED")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    @Override
    public boolean atomicFailInitiated(Object transactionId, String paymentId) {
        Document updated = mongo.getCollection(Collections.TRANSACTIONS).findOneAndUpdate(
                new Document("_id", transactionId).append("status", "INITIATED"),
                new Document("$set", new Document("paymentId", paymentId).append("status", "FAILED")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return updated != null;
    }

    @Override
    public Document findUser(Object userId) {
        return mongo.getCollection(Collections.USERS).find(new Document("_id", userId)).first();
    }

    @Override
    public double creditUserWallet(Object userId, double coins) {
        Document updated = mongo.getCollection(Collections.USERS).findOneAndUpdate(
                new Document("_id", userId),
                new Document("$inc", new Document("wallet", coins)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
                        .projection(new Document("wallet", 1)));
        if (updated == null) throw new IllegalStateException("PAYMENT_USER_NOT_FOUND");
        return num(updated.get("wallet"));
    }

    @Override
    public void insertWalletLedger(Document payloadForWallet) {
        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(payloadForWallet);
    }

    private double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
