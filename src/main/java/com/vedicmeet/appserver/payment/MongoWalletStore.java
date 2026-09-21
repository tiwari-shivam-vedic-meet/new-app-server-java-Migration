package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Production {@link WalletStore} over MongoTemplate — a thin 1:1 mapping of the Node driver calls
 * inside {@code creditAndDebitOnWallet}. SHADOW-ONLY (only exercised via {@link WalletService},
 * which is unwired). See PROMPT_D_STATUS.md.
 */
@Repository
public class MongoWalletStore implements WalletStore {

    private final MongoTemplate mongo;
    private final OngoingSessionExtensionService sessionExtension;

    public MongoWalletStore(MongoTemplate mongo, OngoingSessionExtensionService sessionExtension) {
        this.mongo = mongo;
        this.sessionExtension = sessionExtension;
    }

    @Override
    public Document findUserWallet(Object userId) {
        return mongo.getCollection(Collections.USERS)
                .find(new Document("_id", userId)).projection(new Document("wallet", 1)).first();
    }

    @Override
    public Document findConsultantWallet(Object consultantId) {
        return mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", consultantId))
                .projection(new Document("wallet", 1).append("price", 1)).first();
    }

    @Override
    public void setUserWallet(Object userId, double wallet) {
        mongo.getCollection(Collections.USERS).updateOne(
                new Document("_id", userId), new Document("$set", new Document("wallet", wallet)));
    }

    @Override
    public void setConsultantWallet(Object consultantId, double wallet) {
        mongo.getCollection(Collections.CONSULTANTS).updateOne(
                new Document("_id", consultantId), new Document("$set", new Document("wallet", wallet)));
    }

    @Override
    public boolean incrementUserWallet(Object userId, double amount) {
        Document updated = mongo.getCollection(Collections.USERS).findOneAndUpdate(
                new Document("_id", userId),
                new Document("$inc", new Document("wallet", amount)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
                        .projection(new Document("wallet", 1)));
        return updated != null;
    }

    @Override
    public boolean incrementConsultantWallet(Object consultantId, double amount) {
        Document updated = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", consultantId),
                new Document("$inc", new Document("wallet", amount)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
                        .projection(new Document("wallet", 1)));
        return updated != null;
    }

    @Override
    public boolean debitUserWallet(Object userId, double coins, String transactionFor) {
        Document filter = new Document("_id", userId);
        if (transactionFor != null) {
            if ("consult".equals(transactionFor)) {
                double minimum = coins / 5.0;
                if (coins <= 1) {
                    filter.append("$or", java.util.List.of(
                            new Document("wallet", 0),
                            new Document("wallet", new Document("$gte", minimum))));
                } else {
                    filter.append("wallet", new Document("$gte", minimum));
                }
            } else {
                filter.append("wallet", new Document("$gte", coins));
            }
        }
        Document updated = mongo.getCollection(Collections.USERS).findOneAndUpdate(
                filter,
                new Document("$inc", new Document("wallet", -coins)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
                        .projection(new Document("wallet", 1)));
        return updated != null;
    }

    @Override
    public void extendOngoingSessionTime(Object userId, double coins) {
        sessionExtension.extend(userId, coins);
    }

    @Override
    public void insertLedger(Document payloadForWallet) {
        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(payloadForWallet);
    }
}
