package com.vedicmeet.appserver.call;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.realtime.SocketEventPublisher;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mongo/Redis implementation of the production call-completion effects. */
@Repository
public class MongoRedisCallCompletionStore implements CallCompletionStore {

    private static final Logger log = LoggerFactory.getLogger(MongoRedisCallCompletionStore.class);
    private static final DefaultRedisScript<Long> RELEASE_CONSULTANT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final JavaCallTimerService timers;
    private final SocketEventPublisher events;
    private final PushNotificationService push;
    private final CallContinuationService continuation;

    public MongoRedisCallCompletionStore(MongoTemplate mongo, StringRedisTemplate redis,
                                         JavaCallTimerService timers, SocketEventPublisher events,
                                         PushNotificationService push,
                                         CallContinuationService continuation) {
        this.mongo = mongo;
        this.redis = redis;
        this.timers = timers;
        this.events = events;
        this.push = push;
        this.continuation = continuation;
    }

    @Override
    public Map<String, String> getCallHash(String roomId) {
        Map<Object, Object> raw = redis.opsForHash().entries(roomId);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }

    @Override public Document findWaitlist(String roomId) { return findById(Collections.WAITLISTS, roomId); }
    @Override public Document findUser(String userId) { return findById(Collections.USERS, userId); }
    @Override public Document findConsultant(String consultantId) { return findById(Collections.CONSULTANTS, consultantId); }
    @Override public Document findOfferVariant(Object variantId) { return findById(Collections.OFFER_VARIANTS, variantId); }
    @Override public Document findV2Coupon(Object couponId) { return findById(Collections.V2_COUPONS, couponId); }

    @Override
    public CompletionPolicy evaluateCompletionPolicy(String consultantId, String callEndedBy,
                                                       Document waitlist) {
        Document coupon = doc(waitlist.get("coupon"));
        if (!"first_purchase".equals(coupon.getString("type"))) {
            return new CompletionPolicy(false, false, null);
        }
        try {
            boolean blockPayout = shouldBlockConsultantPayout(consultantId);
            String replacementToken = null;
            boolean showAgain = false;
            if ("user".equals(callEndedBy)) {
                Document settings = mongo.getCollection(Collections.SEED_MASTERS)
                        .find(new Document("for", "userMasterSettings")).first();
                boolean replayEnabled = Boolean.TRUE.equals(doc(settings == null ? null : settings.get("data"))
                        .get("isFirstConsultantionAvailableOnUser1stCallCut"));
                String token = string(waitlist.get("deviceUsedToken"));
                if (replayEnabled && !token.isBlank()) {
                    replacementToken = token + "_previous_taken";
                    Document prior = mongo.getCollection(Collections.WAITLISTS)
                            .find(new Document("deviceUsedToken", replacementToken)
                                    .append("status", "completed")).first();
                    showAgain = prior == null;
                    if (!showAgain) replacementToken = null;
                }
            }
            return new CompletionPolicy(blockPayout, showAgain, replacementToken);
        } catch (RuntimeException error) {
            // Node's conversion helper fails open. Completion must not fail because an optional
            // policy lookup was temporarily unavailable.
            log.warn("first-purchase completion policy lookup failed consultantId={}", consultantId);
            return new CompletionPolicy(false, false, null);
        }
    }

    private boolean shouldBlockConsultantPayout(String consultantId) {
        Document settings = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "consultantMasterSettings")).first();
        if (!Boolean.TRUE.equals(doc(settings == null ? null : settings.get("data"))
                .get("isConsultantClientAlreadyConvertedActive"))) return false;

        Date since = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
        List<Document> lastTwo = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("consultant_id", consultantId).append("status", "completed")
                        .append("createdAt", new Document("$gte", since))
                        .append("coupon.type", "first_purchase"))
                .sort(new Document("timeLap.endTime", -1).append("updatedAt", -1))
                .limit(2).into(new ArrayList<>());
        if (lastTwo.size() < 2) return false;

        int converted = 0;
        for (Document previous : lastTwo) {
            String previousUser = string(previous.get("user_id"));
            Date completedAt = doc(previous.get("timeLap")).getDate("endTime");
            if (completedAt == null) completedAt = previous.getDate("updatedAt");
            if (!ObjectId.isValid(previousUser) || completedAt == null) continue;
            Document paid = mongo.getCollection(Collections.TRANSACTIONS)
                    .find(new Document("userId", new ObjectId(previousUser))
                            .append("status", new Document("$in", List.of("COMPLETED", "paid")))
                            .append("createdAt", new Document("$gte", completedAt)))
                    .projection(new Document("_id", 1)).first();
            if (paid != null) converted++;
        }
        return converted == 0;
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public ApplyResult claimAndApply(String roomId, String userId, String consultantId,
                                     String callStatus, String callEndedBy, Amounts amounts,
                                     Document waitlist, Document user, Document consultant,
                                     CompletionPolicy policy) {
        Date endedAt = new Date();
        Document completion = new Document("baseAmount", amounts.baseAmount())
                .append("amountToDeduct", amounts.baseAmount())
                .append("platformAmount", amounts.platformAmount())
                .append("consultantAmount", amounts.consultantAmount())
                .append("callDurationInSeconds", amounts.callDurationSeconds())
                .append("isAmountRefunded", false)
                .append("extraDuration", amounts.extraDuration())
                .append("extraDurationAmount", amounts.extraDurationAmount());
        Document logEntry = new Document("callStatus", callStatus)
                .append("callEndedBy", callEndedBy).append("timestamp", System.currentTimeMillis());

        List<Document> completionLogs = new ArrayList<>();
        if (policy.blockConsultantPayout()) {
            completionLogs.add(new Document("callStatus", "previous calls not converted")
                    .append("callEndedBy", callEndedBy)
                    .append("note", "Consultant has previous unconverted calls; wallet payout blocked")
                    .append("timestamp", System.currentTimeMillis()));
        }
        completionLogs.add(logEntry);
        Document completionSet = new Document("status", "completed")
                .append("timeLap.endTime", endedAt).append("onCompletion", completion);
        if (policy.replacementDeviceToken() != null) {
            completionSet.append("deviceUsedToken", policy.replacementDeviceToken());
        }

        Document completed = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", id(roomId)).append("status", "progress"),
                new Document("$set", completionSet)
                        .append("$push", new Document("logs", new Document("$each", completionLogs))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (completed == null) return new ApplyResult(false, 0, null);

        Document coupon = doc(waitlist.get("coupon"));
        Document consultantInc = new Document("wallet", Math.max(0, amounts.consultantAmount()));
        Document sessionsStatus = doc(consultant.get("sessionsStatus"));
        Document sessionInfo = doc(waitlist.get("session_info"));
        if (Boolean.TRUE.equals(sessionsStatus.get("canGoOffline"))
                || Boolean.TRUE.equals(sessionInfo.get("isOfflineSession"))) {
            consultantInc.append("limit.flags.current", 1);
        }
        Document consultantUpdate = new Document("$inc", consultantInc);
        if ("first_purchase".equals(coupon.getString("type"))) {
            consultantUpdate.append("$inc", consultantInc.append("quickStats.first_purchase", 1));
        }
        mongo.getCollection(Collections.CONSULTANTS).updateOne(
                new Document("_id", id(consultantId)), consultantUpdate);

        if (Boolean.TRUE.equals(sessionsStatus.get("canGoOffline"))
                || Boolean.TRUE.equals(sessionInfo.get("isOfflineSession"))) {
            mongo.getCollection(Collections.FLAG_LOGS).insertOne(
                    new Document("consultant_id", id(consultantId))
                            .append("flag_type", "offline_session")
                            .append("waitlist_id", id(roomId))
                            .append("temporary_data", new Document("userId", id(userId)))
                            .append("flag_status", "resolved")
                            .append("flag_reason", "Consultant took an offline session")
                            .append("createdAt", endedAt).append("updatedAt", endedAt));
        }

        Object couponId = coupon.get("couponId");
        if (couponId != null) {
            mongo.getCollection(Collections.WAITLISTS).updateMany(
                    new Document("coupon.couponId", couponId).append("status", "waiting")
                            .append("user_id", userId),
                    new Document("$set", new Document("status", "canceled")));
        }

        double actualDeducted = 0;
        boolean session = "session".equals(waitlist.getString("used_for"));
        if (session) {
            mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS).updateOne(
                    new Document("waitlist_id", id(roomId)),
                    new Document("$set", new Document("status", "completed")));
            if (amounts.userAmount() != 0) {
                mongo.getCollection(Collections.USERS).updateOne(
                        new Document("_id", id(userId)),
                        new Document("$inc", new Document("wallet", amounts.userAmount())));
            }
            insertLedger(userId, "user", amounts.userAmount(), amounts.userAmount() > 0 ? 0 : 1,
                    waitlist, consultant, amounts.callDurationSeconds());
        } else {
            double available = number(user.get("wallet"));
            double expected = Math.min(Math.max(0, amounts.baseAmount()), Math.max(0, available));
            if (expected > 0) {
                Document changed = mongo.getCollection(Collections.USERS).findOneAndUpdate(
                        new Document("_id", id(userId)).append("wallet", new Document("$gte", expected)),
                        new Document("$inc", new Document("wallet", -expected)),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (changed != null) actualDeducted = expected;
            }
            insertLedger(userId, "user", actualDeducted, 1, waitlist, consultant,
                    amounts.callDurationSeconds());
            if (expected > 0 && actualDeducted == 0) {
                mongo.getCollection(Collections.TEMPORARY_LOGS).insertOne(
                        new Document("FOR", "call_handle_timeout_insufficient_wallet")
                                .append("userId", userId).append("roomId", roomId)
                                .append("consultantId", consultantId)
                                .append("expectedDeduction", amounts.baseAmount())
                                .append("currentWallet", available).append("createdAt", endedAt));
            }
        }

        insertConsultantLedger(consultantId, user, waitlist, amounts, consultant);
        return new ApplyResult(true, actualDeducted, completed);
    }

    private void insertLedger(String actorId, String userType, double coins, int transactionType,
                              Document waitlist, Document consultant, double durationSeconds) {
        Document meta = new Document("sessionId", String.valueOf(waitlist.get("_id")))
                .append("orderId", waitlist.get("orderId"))
                .append("name", consultant.get("accountName"))
                .append("mode", doc(waitlist.get("session_info")).get("mode"))
                .append("callDuration", durationSeconds / 60.0);
        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(
                new Document("userId", id(actorId)).append("userType", userType)
                        .append("transactionFor", "consult").append("coins", coins)
                        .append("transactionType", transactionType).append("isConsTransfer", false)
                        .append("meta", meta).append("createdAt", new Date()).append("updatedAt", new Date()));
    }

    private void insertConsultantLedger(String consultantId, Document user, Document waitlist,
                                        Amounts amounts, Document consultant) {
        Document requestForm = doc(waitlist.get("request_form"));
        String displayName = string(requestForm.get("firstName")) + " " + string(requestForm.get("lastName"));
        Document meta = new Document("sessionId", String.valueOf(waitlist.get("_id")))
                .append("orderId", waitlist.get("orderId"))
                .append("userId", user.get("userId") == null ? String.valueOf(user.get("_id")) : user.get("userId"))
                .append("name", displayName.trim())
                .append("mode", doc(waitlist.get("session_info")).get("mode"))
                .append("callDuration", amounts.callDurationSeconds() / 60.0);
        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(
                new Document("userId", id(consultantId)).append("userType", "cons")
                        .append("transactionFor", "consult").append("coins", amounts.consultantAmount())
                        .append("transactionType", 0).append("isConsTransfer", false)
                        .append("meta", meta).append("createdAt", new Date()).append("updatedAt", new Date()));
    }

    @Override
    public void clearRuntime(String roomId, String consultantId) {
        try { redis.opsForHash().put(roomId, "status", "completed"); } catch (RuntimeException ignored) {}
        try { timers.cancelTimeout(roomId); } catch (RuntimeException ignored) {}
        try { timers.cancelMissed(roomId); } catch (RuntimeException ignored) {}
        try { redis.delete(roomId); } catch (RuntimeException ignored) {}
        try {
            redis.execute(RELEASE_CONSULTANT,
                    List.of("lock:consultant-call:" + consultantId), roomId);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void emitLeaveRooms(String userId, String consultantId, Document waitlist,
                               String callEndedBy, boolean showFirstConsultationAgain) {
        if ("live_event".equals(waitlist.getString("used_for"))) {
            Map<String, Object> userPayload = new LinkedHashMap<>();
            userPayload.put("waitlist", waitlist);
            userPayload.put("roomId", waitlist.get("threadId"));
            userPayload.put("callEndedBy", callEndedBy);
            events.emitToRoom("live_event", userId, "session_ended_leave_chat", userPayload);

            Document request = doc(waitlist.get("request_form"));
            Map<String, Object> consultantPayload = new LinkedHashMap<>(userPayload);
            consultantPayload.put("user", Map.of("_id", userId,
                    "name", string(request.get("firstName"), "User"), "profileImage", ""));
            events.emitToRoom("live_event", consultantId, "session_ended_leave_chat",
                    consultantPayload);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", waitlist.get("threadId"));
        payload.put("waitlistId", String.valueOf(waitlist.get("_id")));
        payload.put("callEndedBy", callEndedBy);
        payload.put("isNeedToShowAgainFirstConsultation", showFirstConsultationAgain);
        events.emitToRoom(userId, "leave_call_room", payload);
        events.emitToRoom(consultantId, "leave_call_room", payload);
    }

    @Override
    public void notifyCallEnded(String userId, String consultantId, Document user, Document consultant,
                                Document waitlist, Amounts amounts, double actualUserDeduction) {
        Document session = doc(waitlist.get("session_info"));
        if (!List.of("audio", "video").contains(session.getString("mode"))) return;
        Map<String, Object> data = Map.of("sentAt", System.currentTimeMillis(),
                "type", "session_ended", "sessionId", String.valueOf(waitlist.get("_id")));
        for (String token : tokens(consultant)) {
            push.send("cons", token, "Call Ended",
                    string(user.get("name"), "User") + " ended the call", data,
                    null, false, Map.of());
        }
        for (String token : tokens(user)) {
            push.send("user", token, "Call Ended",
                    string(consultant.get("accountName"), "Consultant") + " ended the call",
                    data, null, false, Map.of());
        }
    }

    @Override
    public void deleteCallInitiated(String roomId, String userId, String consultantId) {
        mongo.getCollection(Collections.CALL_INITIATED).deleteMany(
                new Document("userId", userId).append("consultantId", consultantId)
                        .append("consultantPayload.sessionId", roomId)
                        .append("userPayload.sessionId", roomId));
    }

    @Override
    public void enqueuePostCallWork(String userId, String consultantId, Document waitlist,
                                    Amounts amounts, double actualUserDeduction, String callEndedBy) {
        String id = "CALL_POST:" + waitlist.get("_id");
        Document payload = new Document("userId", userId).append("consultantId", consultantId)
                .append("waitlistId", String.valueOf(waitlist.get("_id")))
                .append("threadId", waitlist.get("threadId"))
                .append("callEndedBy", callEndedBy)
                .append("sessionTiming", waitlist.get("timeLap"))
                .append("usedFor", waitlist.get("used_for"))
                .append("bookType", doc(waitlist.get("session_info")).get("bookType"))
                .append("sessionType", amounts.sessionType())
                .append("callDurationSeconds", amounts.callDurationSeconds())
                .append("actualUserDeduction", actualUserDeduction)
                .append("amounts", new Document("base", amounts.baseAmount())
                        .append("consultant", amounts.consultantAmount())
                        .append("platform", amounts.platformAmount())
                        .append("user", amounts.userAmount()));
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", id),
                new Document("$setOnInsert", new Document("type", "CALL_POST_PROCESSING")
                        .append("payload", payload).append("status", "PENDING")
                        .append("owner", "java").append("attempts", 0)
                        .append("nextAttemptAt", new Date()).append("createdAt", new Date())),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    @Override
    public void callNextWaitingUser(Document consultant, String completedRoomId, String callEndedBy) {
        Document completed = findById(Collections.WAITLISTS, completedRoomId);
        // The Node /live_event protocol lets the consultant explicitly emit call_next_user.
        // Do not leak a live-event completion into the ordinary private-call queue.
        if (completed != null && "live_event".equals(completed.getString("used_for"))) return;
        continuation.afterCall(consultant, completedRoomId, callEndedBy);
    }

    private Document findById(String collection, Object rawId) {
        if (rawId == null) return null;
        return mongo.getCollection(collection).find(new Document("_id", id(rawId))).first();
    }

    private Object id(Object value) {
        if (value instanceof ObjectId) return value;
        String text = String.valueOf(value);
        return ObjectId.isValid(text) ? new ObjectId(text) : value;
    }

    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String string(Object value, String fallback) {
        String text = string(value);
        return text.isBlank() ? fallback : text;
    }
    @SuppressWarnings("unchecked")
    private List<String> tokens(Document actor) {
        Document device = doc(actor.get("device"));
        Object tokens = device.get("fcmToken");
        return tokens instanceof List<?> list
                ? list.stream().filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(String::valueOf).toList() : List.of();
    }
}
