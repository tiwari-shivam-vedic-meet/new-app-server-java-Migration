package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ports the best-effort post-call block in Node call.js handleCallTimeout. */
@Service
public class CallPostProcessingService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final CallPostCallMoneyService money;
    private final PushNotificationService push;
    private final CleverTapClient cleverTap;
    private final ChatServerClient chat;

    public CallPostProcessingService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                     CallPostCallMoneyService money, PushNotificationService push,
                                     CleverTapClient cleverTap, ChatServerClient chat) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.money = money;
        this.push = push;
        this.cleverTap = cleverTap;
        this.chat = chat;
    }

    public void process(CallIntegrationOutboxService.ClaimedJob job) {
        Document payload = job.payload();
        Document user = require(Collections.USERS, payload.get("userId"), "USER_NOT_FOUND");
        Document consultant = require(Collections.CONSULTANTS, payload.get("consultantId"),
                "CONSULTANT_NOT_FOUND");
        Document waitlist = require(Collections.WAITLISTS, payload.get("waitlistId"),
                "WAITLIST_NOT_FOUND");

        double reward = money.apply(job);
        step(job, "wallet-notifications", () -> walletNotifications(job.id(), payload,
                user, consultant, reward));
        step(job, "consultation-coupons", () -> couponNotifications(job.id(), user));
        step(job, "clevertap", () -> cleverTapEvent(payload, user, waitlist));
        step(job, "chat-summary", () -> chatSummary(payload, waitlist));
    }

    private void walletNotifications(String jobId, Document payload, Document user,
                                     Document consultant, double reward) {
        Document amounts = doc(payload.get("amounts"));
        double consultantAmount = number(amounts.get("consultant"));
        if (consultantAmount > 0) {
            walletNotification(jobId + ":consultant-credit", consultant, "cons",
                    consultantAmount, true);
        }
        double userAmount = number(amounts.get("user"));
        if ("session".equals(payload.getString("usedFor")) && userAmount != 0) {
            walletNotification(jobId + ":session-user", user, "user", userAmount,
                    userAmount > 0);
        }
        double deducted = number(payload.get("actualUserDeduction"));
        if (!"session".equals(payload.getString("usedFor")) && deducted > 0
                && !"CHAT_AUDIO_LIMITLESS".equals(payload.getString("sessionType"))) {
            walletNotification(jobId + ":user-debit", user, "user", deducted, false);
        }
        if (reward > 0 && !"5COINSPERMIN_99AUDIOENDLESS".equals(payload.getString("sessionType"))) {
            walletNotification(jobId + ":special-reward", user, "user", reward, true);
        }
    }

    private void walletNotification(String notificationId, Document actor, String userType,
                                    double coins, boolean added) {
        String amount = String.format(Locale.ROOT, "%.2f", coins);
        String message = added ? amount + " Coins have been added to your wallet."
                : " " + amount + " Coins have been deducted from your wallet.";
        Map<String, Object> data = Map.of("screen", "MyWallet", "coins", coins);
        for (String token : tokens(actor)) {
            push.sendNotificationAndCons(userType, token, message, data,
                    "Vedic Meet", userType);
        }
        saveNotification(notificationId, actor.get("_id"), userType,
                "Vedic Meet", message, data);
    }

    private void couponNotifications(String jobId, Document user) {
        long count = mongo.getCollection(Collections.WAITLISTS).countDocuments(
                new Document("user_id", String.valueOf(user.get("_id"))).append("status", "completed"));
        if (count != 1 && count != 2) return;

        String firstTitle = count == 1 ? "Chat/Call ended?" : "Loved your consultation?";
        String firstBody = count == 1
                ? "Continue your session at just ₹5/min!"
                : "Get 50% off on your next recharge-save for future consultations!";
        String secondTitle = "20% Cashback on Next Recharge!";
        String secondBody = "Offer expires in 20 minutes";
        Map<String, Object> data = Map.of("screen", "ConsultancyScreen", "coupon", "FIVEMIN");
        for (String token : tokens(user)) {
            push.sendNotificationAndCons("user", token, firstBody, data, firstTitle, "user");
            push.sendNotificationAndCons("user", token, secondBody, data, secondTitle, "user");
        }
        saveNotification(jobId + ":consultation-coupon", user.get("_id"), "user",
                firstTitle, firstBody, data);
    }

    private void cleverTapEvent(Document payload, Document user, Document waitlist) {
        if (!cleverTap.isReady()) return;
        Document details = doc(user.get("details"));
        String identity = text(details.get("phone"));
        if (identity.isBlank()) return;
        boolean firstPurchase = "first_purchase".equals(doc(waitlist.get("coupon")).getString("type"));
        cleverTap.uploadEvent(identity, firstPurchase ? "FREE_CONSULTATIONS" : "PAID_CONSULTATIONS",
                Map.of("action", "consultation_completed", "timestamp", new Date().toInstant().toString(),
                        "source", "server", "amount", firstPurchase ? 0 : number(doc(payload.get("amounts")).get("base"))));
    }

    private void chatSummary(Document payload, Document waitlist) {
        Document timing = doc(payload.get("sessionTiming"));
        Map<String, Object> sessionTiming = new LinkedHashMap<>();
        sessionTiming.put("startTime", timing.get("startTime"));
        sessionTiming.put("endTime", timing.get("endTime") == null ? new Date() : timing.get("endTime"));
        // Map.of rejects null values. Old waitlists can legitimately have no threadId, and the
        // Node post-call block treats chat summary as best effort rather than failing settlement.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionId", text(payload.get("waitlistId")));
        summary.put("threadId", value(payload.get("threadId"), waitlist.get("threadId")));
        summary.put("consultantId", text(payload.get("consultantId")));
        summary.put("userId", text(payload.get("userId")));
        summary.put("sessionTiming", sessionTiming);
        chat.createSummary(summary);
    }

    private void step(CallIntegrationOutboxService.ClaimedJob job, String name, Runnable action) {
        if (outbox.stepCompleted(job, name)) return;
        action.run();
        outbox.markStep(job.id(), name);
    }

    private void saveNotification(String id, Object receiverId, String userType,
                                  String title, String message, Map<String, Object> data) {
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATIONS).updateOne(new Document("_id", id),
                new Document("$setOnInsert", new Document("receiverId", objectId(receiverId))
                        .append("userType", userType).append("senderType", "system")
                        .append("type", "other").append("title", title).append("message", message)
                        .append("data", data).append("isRead", false).append("status", true)
                        .append("readByReceiver", List.of()).append("createdAt", now).append("updatedAt", now)
                        .append("expireAt", new Date(now.getTime() + 7L * 24 * 60 * 60 * 1000))),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    private Document require(String collection, Object id, String message) {
        Document value = mongo.getCollection(collection).find(new Document("_id", objectId(id))).first();
        if (value == null) throw new IllegalStateException(message);
        return value;
    }

    private Object objectId(Object value) {
        if (value instanceof ObjectId) return value;
        String text = text(value);
        return ObjectId.isValid(text) ? new ObjectId(text) : value;
    }

    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private Object value(Object first, Object second) { return first == null ? second : first; }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private List<String> tokens(Document actor) {
        Object value = doc(actor.get("device")).get("fcmToken");
        return value instanceof List<?> list ? list.stream().filter(v -> v != null)
                .map(String::valueOf).filter(v -> !v.isBlank()).toList() : List.of();
    }
}
