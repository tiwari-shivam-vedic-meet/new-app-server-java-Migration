package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;

/** Refund request and immediate-refund parity for consultant/session.js. */
@Service
public class ConsultantRefundService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final TransactionTemplate transactions;

    public ConsultantRefundService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                   MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Document requestRefund(Document actor, String waitlistId) {
        String consultantId = requiredActor(actor);
        Object waitlistKey = blank(waitlistId) ? null : validatedId(waitlistId);
        Document duplicateFilter = new Document("consultant_id",
                new Document("$in", MongoIds.variants(actor.get("_id"))))
                .append("flag_type", "refund_request")
                .append("waitlist_id", waitlistKey == null ? null
                        : new Document("$in", MongoIds.variants(waitlistKey)));
        if (mongo.getCollection(Collections.FLAG_LOGS).find(duplicateFilter).first() != null) {
            throw new IllegalStateException("Refund request already submitted");
        }

        Document waitlist = null;
        if (waitlistKey != null) {
            waitlist = mongo.getCollection(Collections.WAITLISTS).find(
                    new Document("_id", waitlistKey)
                            .append("consultant_id", new Document("$in", MongoIds.variants(consultantId))))
                    .first();
            if (waitlist == null) throw new IllegalArgumentException("Waitlist not found");
        }
        Document completion = doc(waitlist == null ? null : waitlist.get("onCompletion"));
        Date now = new Date();
        Document flag = new Document("_id", new ObjectId())
                .append("consultant_id", actor.get("_id"))
                .append("flag_type", "refund_request")
                .append("waitlist_id", waitlistId)
                .append("temporary_data", new Document("userAmount", completion.get("amountToDeduct"))
                        .append("consultantAmount", completion.get("consultantAmount")))
                .append("flag_status", "pending")
                .append("flag_reason", "Consultant requested refund")
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.FLAG_LOGS).insertOne(flag);
        return flag;
    }

    /**
     * All wallet, ledger and waitlist writes commit together. The first atomic refund claim wins;
     * duplicate HTTP requests and competing Node/Java callbacks cannot credit twice.
     */
    public Document refundImmediately(Document actor, String waitlistId) {
        String consultantId = requiredActor(actor);
        Object key = validatedId(waitlistId);

        Document current = mongo.getCollection(Collections.WAITLISTS).find(
                new Document("_id", key)
                        .append("consultant_id", new Document("$in", MongoIds.variants(consultantId)))).first();
        validateRefundable(current);

        Document result = transactions.execute(status -> {
            Date now = new Date();
            Document claimed = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                    new Document("_id", key)
                            .append("consultant_id", new Document("$in", MongoIds.variants(consultantId)))
                            .append("status", "completed")
                            .append("coupon.type", new Document("$ne", "first_purchase"))
                            .append("onCompletion.isAmountRefunded", new Document("$ne", true)),
                    new Document("$set", new Document("onCompletion.isAmountRefunded", true)
                            .append("updatedAt", now))
                            .append("$push", new Document("logs", new Document("callStatus", "refunded")
                                    .append("timestamp", System.currentTimeMillis()))),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
            if (claimed == null) throw new IllegalStateException("Amount already refunded");

            Document completion = doc(claimed.get("onCompletion"));
            double userAmount = number(completion.get("amountToDeduct"));
            double consultantAmount = number(completion.get("consultantAmount"));
            Object userId = MongoIds.id(claimed.get("user_id"));
            Object consultantObjectId = MongoIds.id(consultantId);

            if (mongo.getCollection(Collections.USERS).updateOne(new Document("_id", userId),
                    new Document("$inc", new Document("wallet", userAmount))).getMatchedCount() == 0) {
                throw new IllegalStateException("User not found");
            }
            Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                    .find(new Document("_id", consultantObjectId)).first();
            if (consultant == null) throw new IllegalStateException("Consultant not found");
            double newWallet = Math.max(0, number(consultant.get("wallet")) - consultantAmount);
            mongo.getCollection(Collections.CONSULTANTS).updateOne(
                    new Document("_id", consultantObjectId),
                    new Document("$set", new Document("wallet", newWallet).append("updatedAt", now)));

            mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertMany(List.of(
                    ledger(claimed, userId, "user", userAmount, 0, now),
                    ledger(claimed, consultantObjectId, "cons", consultantAmount, 1, now)));
            mongo.getCollection(Collections.WAITLISTS).updateOne(new Document("_id", key),
                    new Document("$set", new Document("onCompletion.consultantAmount", 0)
                            .append("updatedAt", now)));

            outbox.enqueue("CONSULTANT_REFUND_NOTIFY:" + waitlistId, "CONSULTANT_REFUND_NOTIFY",
                    new Document("waitlistId", waitlistId)
                            .append("userId", text(claimed.get("user_id")))
                            .append("consultantId", consultantId)
                            .append("userAmount", userAmount)
                            .append("consultantAmount", consultantAmount));
            return mongo.getCollection(Collections.WAITLISTS).find(new Document("_id", key)).first();
        });
        if (result == null) throw new IllegalStateException("Refund transaction failed");
        return result;
    }

    private Document ledger(Document waitlist, Object userId, String userType, double coins,
                            int transactionType, Date now) {
        Document request = doc(waitlist.get("request_form"));
        Document info = doc(waitlist.get("session_info"));
        Document completion = doc(waitlist.get("onCompletion"));
        return new Document("_id", new ObjectId()).append("userId", userId)
                .append("userType", userType).append("transactionFor", "refund")
                .append("coins", coins).append("transactionType", transactionType)
                .append("isConsTransfer", false)
                .append("meta", new Document("sessionId", text(waitlist.get("_id")))
                        .append("orderId", waitlist.get("orderId"))
                        .append("name", request.get("firstName"))
                        .append("mode", info.get("mode"))
                        .append("callDuration", number(completion.get("callDurationInSeconds")) / 60d))
                .append("createdAt", now).append("updatedAt", now);
    }

    private void validateRefundable(Document waitlist) {
        if (waitlist == null) throw new IllegalArgumentException("Waitlist not found");
        if ("first_purchase".equals(text(doc(waitlist.get("coupon")).get("type")))) {
            throw new IllegalStateException("First purchase coupon cannot be refunded");
        }
        if (Boolean.TRUE.equals(doc(waitlist.get("onCompletion")).get("isAmountRefunded"))) {
            throw new IllegalStateException("Amount already refunded");
        }
        if (!"completed".equals(text(waitlist.get("status")))) {
            throw new IllegalStateException("Can only refund completed sessions");
        }
    }

    private Object validatedId(String value) {
        if (blank(value) || !ObjectId.isValid(value)) {
            throw new IllegalArgumentException("Valid waitlistId is required");
        }
        return new ObjectId(value);
    }
    private String requiredActor(Document actor) {
        String id = actor == null ? "" : text(actor.get("_id"));
        if (blank(id)) throw new IllegalStateException("Consultant not found");
        return id;
    }
    private static Document doc(Object value) {
        return value instanceof Document document ? document : new Document();
    }
    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
