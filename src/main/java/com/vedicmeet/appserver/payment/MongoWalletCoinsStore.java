package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Production {@link WalletCoinsStore} over the {@code wallets} collection (Node WalletModel).
 * SHADOW-ONLY (only reached via {@link AesWalletService}, which is unwired).
 */
@Repository
public class MongoWalletCoinsStore implements WalletCoinsStore {

    private final MongoTemplate mongo;

    public MongoWalletCoinsStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public String findUserCoinsCipher(Object userId) {
        return coinsOf(mongo.getCollection(Collections.WALLETS)
                .find(new Document("userId", userId)).projection(new Document("coins", 1)).first());
    }

    @Override
    public void setUserCoinsCipher(Object userId, String cipher) {
        mongo.getCollection(Collections.WALLETS).updateOne(
                new Document("userId", userId), new Document("$set", new Document("coins", cipher)));
    }

    @Override
    public String findConsultantCoinsCipher(Object consultantId) {
        return coinsOf(mongo.getCollection(Collections.WALLETS)
                .find(new Document("consultantId", consultantId)).projection(new Document("coins", 1)).first());
    }

    @Override
    public void setConsultantCoinsCipher(Object consultantId, String cipher) {
        mongo.getCollection(Collections.WALLETS).updateOne(
                new Document("consultantId", consultantId), new Document("$set", new Document("coins", cipher)));
    }

    private String coinsOf(Document walletDoc) {
        if (walletDoc == null) return null;
        Object coins = walletDoc.get("coins");
        return coins == null ? null : String.valueOf(coins);
    }
}
