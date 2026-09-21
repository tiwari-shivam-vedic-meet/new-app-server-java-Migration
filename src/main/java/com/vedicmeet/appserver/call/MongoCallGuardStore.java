package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Production {@link CallGuardStore}. SHADOW-ONLY (only reached via {@link CallLifecycleService},
 * which is unwired). The DB gates are 1:1 with Node; the pricing and redis/socket effects are honest
 * seams that throw until wired (they belong to the pricing engine + Prompt F real-time layer).
 */
@Repository
public class MongoCallGuardStore implements CallGuardStore {

    private static final Logger log = LoggerFactory.getLogger(MongoCallGuardStore.class);

    private final MongoTemplate mongo;
    private final CallOpenService callOpen;
    private final CallQueueService queue;
    private final PushNotificationService push;
    private final ObjectProvider<CallLifecycleService> lifecycleProvider;

    public MongoCallGuardStore(MongoTemplate mongo, CallOpenService callOpen,
                               CallQueueService queue,
                               PushNotificationService push,
                               ObjectProvider<CallLifecycleService> lifecycleProvider) {
        this.mongo = mongo;
        this.callOpen = callOpen;
        this.queue = queue;
        this.push = push;
        this.lifecycleProvider = lifecycleProvider;
    }

    @Override
    public Document loadUser(String userId) {
        return mongo.getCollection(Collections.USERS)
                .find(new Document("_id", new ObjectId(userId)).append("isDeleted", false)).first();
    }

    @Override
    public Document loadConsultant(String consultantId) {
        return mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", new ObjectId(consultantId))).first();
    }

    @Override
    public String loadWaitlistStatus(String roomId) {
        Document w = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", new ObjectId(roomId))).projection(new Document("status", 1)).first();
        return w == null ? null : w.getString("status");
    }

    @Override
    public boolean hasMinimumBalance(Document user, Document consultant, String callMode, String roomId) {
        Document w = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", new ObjectId(roomId)))
                .projection(new Document("requested_time", 1).append("coupon", 1)
                        .append("session_info", 1).append("used_for", 1)).first();
        if (w == null) return true; // Node optional chaining lets the missing projection fall through.
        double wallet = num(user.get("wallet"));
        double requestedTime = w == null ? 0 : num(w.get("requested_time"));
        Document price = consultant.get("price") instanceof Document ? (Document) consultant.get("price") : null;
        double pricePerUnit = price == null ? 0 : num(price.get("default"));
        Document coupon = w.get("coupon") instanceof Document d ? d : null;
        Document session = w.get("session_info") instanceof Document d ? d : new Document();

        if (coupon != null && "first_purchase".equals(coupon.getString("type"))) {
            Document settings = mongo.getCollection(Collections.SEED_MASTERS)
                    .find(new Document("for", "forFirstEndlessConsultation")).first();
            Document data = settings != null && settings.get("data") instanceof Document d ? d : null;
            if (data == null) return true;
            double required = "chat".equals(callMode)
                    ? num(data.get("chatSessionTime")) : num(data.get("AudioSessionTime")) * 5;
            return wallet >= required;
        }

        if (coupon != null && "second_purchase".equals(coupon.getString("type"))) {
            Document settings = mongo.getCollection(Collections.SEED_MASTERS)
                    .find(new Document("for", "forSecondEndlessConsultation")).first();
            Document data = settings != null && settings.get("data") instanceof Document d ? d : null;
            if (data == null) return true;
            double required = "5COINSPERMINUTE".equals(session.getString("bookType"))
                    ? num(data.get("chatSessionTime")) * 5 : 99;
            return wallet >= required;
        }

        if (coupon != null && "5COINSPERMIN_99AUDIOENDLESS".equals(coupon.getString("code"))) {
            double required = "5COINSPERMINUTE".equals(session.getString("bookType")) ? 25 : 99;
            return wallet >= required;
        }

        if (coupon == null && !"session".equals(w.getString("used_for"))) {
            double requiredBalance = (requestedTime / 60.0) * pricePerUnit * 0.75;
            return wallet >= requiredBalance;
        }
        return true;
    }

    private double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    @Override
    public String busyParty(String userId, String consultantId) {
        Document busy = mongo.getCollection(Collections.WAITLISTS).find(new Document("$or", Arrays.asList(
                new Document("user_id", userId).append("status", "progress"),
                new Document("consultant_id", consultantId)
                        .append("status", new Document("$in", Arrays.asList("progress", "initiated")))))).first();
        if (busy == null) return null;
        return userId.equals(busy.getString("user_id")) ? "user" : "consultant";
    }

    @Override
    public void cancelWaitlist(String roomId, String logMessage) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(
                new Document("_id", new ObjectId(roomId)),
                new Document("$set", new Document("status", "canceled"))
                        .append("$push", new Document("logs",
                                new Document("callStatus", logMessage).append("timestamp", new Date().getTime()))));
    }

    @Override
    public void setMissed(String roomId, String logMessage) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(
                new Document("_id", new ObjectId(roomId)).append("status", "waiting"),
                new Document("$set", new Document("status", "missed"))
                        .append("$push", new Document("logs",
                                new Document("callStatus", logMessage).append("timestamp", new Date().getTime()))));
    }

    @Override
    public void callToNextUser(String consultantId, String reason) {
        Document consultant = loadConsultant(consultantId);
        CallQueueService.NextCaller next = consultant == null ? null : queue.sendCallNotificationToNextUser(consultant);
        CallLifecycleService lifecycle = lifecycleProvider.getIfAvailable();
        if (next != null && lifecycle != null) {
            lifecycle.initiateCall(next.userId, next.consultantId, next.roomId, next.callMode);
        } else {
            log.debug("no next waiting user consultantId={} reason={}", consultantId, reason);
        }
    }

    @Override
    public void openCall(String roomId, String userId, String consultantId, String callMode) {
        callOpen.openCall("user", userId, consultantId, roomId, callMode, true);
    }

    @Override
    public String validateUserReachability(Document user, Document consultant,
                                           String userId, String consultantId, String roomId) {
        Document device = user.get("device") instanceof Document d ? d : new Document();
        Object rawTokens = device.get("fcmToken");
        List<?> tokens = rawTokens instanceof List<?> list ? list : List.of();
        String lastToken = tokens.isEmpty() || tokens.get(tokens.size() - 1) == null
                ? null : String.valueOf(tokens.get(tokens.size() - 1));
        if (lastToken == null || lastToken.isBlank()) {
            clearTokenCancelAndContinue(userId, consultantId, roomId, "no FCM token", true);
            return "no FCM token";
        }

        boolean delivered = push.send("user", lastToken, "Good News!",
                string(consultant.get("accountName"), "Consultant") + " is available for call",
                Map.of(), null, false, Map.of());
        if (!delivered) {
            clearTokenCancelAndContinue(userId, consultantId, roomId, "user uninstalled", false);
            return "user uninstalled";
        }
        return null;
    }

    private void clearTokenCancelAndContinue(String userId, String consultantId, String roomId,
                                             String reason, boolean currentOnly) {
        Document filter = currentOnly
                ? new Document("_id", new ObjectId(roomId))
                : new Document("user_id", userId)
                        .append("status", new Document("$in", List.of("waiting", "missed")));
        mongo.getCollection(Collections.WAITLISTS).updateMany(filter,
                new Document("$set", new Document("status", "canceled"))
                        .append("$push", new Document("logs", new Document("callStatus",
                                        "cancelled due to " + reason)
                                .append("callEndedBy", "user")
                                .append("timestamp", System.currentTimeMillis()))));
        mongo.getCollection(Collections.USERS).updateOne(new Document("_id", new ObjectId(userId)),
                new Document("$set", new Document("device.fcmToken", List.of())));
        callToNextUser(consultantId, "due to " + reason);
    }

    private String string(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
