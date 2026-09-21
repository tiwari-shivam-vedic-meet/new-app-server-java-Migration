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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Concrete Mongo/Redis/socket adapter for all already-ported call state services. */
@Repository
public class MongoRedisCallRuntimeStore implements CallOpenStore, CallAcceptStore, CallEndStore,
        CallCancelStore, CallExtendStore, CallQueueStore {

    private static final Logger log = LoggerFactory.getLogger(MongoRedisCallRuntimeStore.class);
    private static final DefaultRedisScript<Long> RELEASE_CONSULTANT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final AtomicCallAcceptanceRepository acceptance;
    private final JavaCallTimerService timers;
    private final SocketEventPublisher events;
    private final PushNotificationService push;
    private final ObjectProvider<CallLifecycleService> lifecycleProvider;

    public MongoRedisCallRuntimeStore(MongoTemplate mongo, StringRedisTemplate redis,
                                      AtomicCallAcceptanceRepository acceptance,
                                      JavaCallTimerService timers, SocketEventPublisher events,
                                      PushNotificationService push,
                                      ObjectProvider<CallLifecycleService> lifecycleProvider) {
        this.mongo = mongo;
        this.redis = redis;
        this.acceptance = acceptance;
        this.timers = timers;
        this.events = events;
        this.push = push;
        this.lifecycleProvider = lifecycleProvider;
    }

    @Override public void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    @Override
    public boolean claimConsultantCall(String consultantId, String roomId, long ttlSeconds) {
        String key = consultantLock(consultantId);
        Boolean won = redis.opsForValue().setIfAbsent(key, roomId, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(won) || roomId.equals(redis.opsForValue().get(key));
    }

    @Override
    public void releaseConsultantCall(String consultantId, String roomId) {
        redis.execute(RELEASE_CONSULTANT, List.of(consultantLock(consultantId)), roomId);
    }

    @Override
    public Document findBusyWaitlist(String userId, String consultantId) {
        return findBusyWaitlist(userId, consultantId, null);
    }

    @Override
    public Document findBusyWaitlist(String userId, String consultantId, String currentRoomId) {
        Document filter = new Document("$or", List.of(
                new Document("user_id", userId).append("status", "progress"),
                new Document("consultant_id", consultantId)
                        .append("status", new Document("$in", List.of("progress", "initiated")))));
        if (currentRoomId != null) filter.append("_id", new Document("$ne", id(currentRoomId)));
        return mongo.getCollection(Collections.WAITLISTS).find(filter).first();
    }

    @Override public void deleteCallHash(String roomId) { redis.delete(roomId); }

    @Override
    public void writeCallHash(String roomId, Map<String, Object> hash, long ttlSeconds) {
        Map<String, String> values = new LinkedHashMap<>();
        hash.forEach((key, value) -> values.put(key, value == null ? "" : String.valueOf(value)));
        redis.opsForHash().putAll(roomId, values);
        redis.expire(roomId, Duration.ofSeconds(ttlSeconds));
    }

    @Override public Document findUserForCall(String userId) { return findById(Collections.USERS, userId); }
    @Override public Document findConsultantForCall(String consultantId) { return findById(Collections.CONSULTANTS, consultantId); }

    @Override
    public void createCallInitated(String userId, String consultantId,
                                   Map<String, Object> userPayload, Map<String, Object> consultantPayload) {
        mongo.getCollection(Collections.CALL_INITIATED).insertOne(new Document("userId", userId)
                .append("consultantId", consultantId).append("roomId", userPayload.get("sessionId"))
                .append("userPayload", userPayload).append("consultantPayload", consultantPayload)
                .append("isAcceptedByUser", false).append("isAcceptedByConsultant", false)
                .append("isAcceptedByUserForVOIP", false).append("isAcceptedByConsultantForVOIP", false)
                .append("createdAt", new Date()));
    }

    @Override
    public void sendFcm(String token, String title, String body, String userType, Map<String, Object> data) {
        push.send(userType, token, title, body, data, null, true, Map.of());
    }

    @Override
    public void sendVoip(String voipToken, String userType, String title, String body,
                         String name, Map<String, Object> payload) {
        push.sendVoip(voipToken, userType, payload);
    }

    @Override
    public void pushInitiatedLog(String roomId, String callBy) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(new Document("_id", id(roomId)),
                new Document("$set", new Document("status", "initiated"))
                        .append("$push", new Document("logs", new Document("callBy", callBy)
                                .append("callStatus", "initiated").append("timestamp", System.currentTimeMillis()))));
    }

    @Override
    public void registerMissedTimer(String roomId, long seconds) {
        Map<String, String> call = readCallHash(roomId);
        timers.scheduleMissed(roomId, call.get("userId"), call.get("consultantId"), "call", seconds);
    }

    @Override public Map<String, String> readCallHash(String roomId) { return getCallHash(roomId); }

    // ---- acceptance ----

    @Override
    public AcceptanceClaim claimAcceptance(String roomId, String acceptorId, boolean reconnect,
                                            long acceptedAtMillis) {
        return acceptance.claim(roomId, acceptorId, reconnect, acceptedAtMillis);
    }

    @Override
    public void markAccepted(String userId, String consultantId) {
        if (userId != null) {
            mongo.getCollection(Collections.CALL_INITIATED).findOneAndUpdate(
                    new Document("userId", userId).append("isAcceptedByUser", false),
                    new Document("$set", new Document("isAcceptedByUser", true)));
        } else if (consultantId != null) {
            mongo.getCollection(Collections.CALL_INITIATED).findOneAndUpdate(
                    new Document("consultantId", consultantId).append("isAcceptedByConsultant", false),
                    new Document("$set", new Document("isAcceptedByConsultant", true)));
        }
    }

    @Override public void emit(String room, String event, Object payload) { events.emitToRoom(room, event, payload); }

    @Override
    public void pushWaitlistLog(String roomId, String actionBy, String callStatus) {
        mongo.getCollection(Collections.WAITLISTS).updateOne(new Document("_id", id(roomId)),
                new Document("$push", new Document("logs", new Document("actionBy", actionBy)
                        .append("callStatus", callStatus).append("timestamp", System.currentTimeMillis()))));
    }

    @Override public void cancelMissedTimer(String roomId) { timers.cancelMissed(roomId); }

    @Override
    public Document progressWaitlist(String roomId) {
        return mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", id(roomId)).append("status", "initiated"),
                new Document("$set", new Document("status", "progress")
                        .append("timeLap", new Document("startTime", new Date())))
                        .append("$push", new Document("logs", new Document("callStatus", "started")
                                .append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    @Override
    public void startDurationTimer(String roomId, long durationSeconds) {
        Map<String, String> call = getCallHash(roomId);
        timers.scheduleTimeout(roomId, call.get("userId"), call.get("consultantId"), durationSeconds);
    }

    @Override public Document findWaitlist(String roomId) { return findById(Collections.WAITLISTS, roomId); }

    @Override
    public void emitJoinCallRoom(String toRoom, Map<String, Object> payload) {
        events.emitToRoom(toRoom, "join_call_room", payload);
    }

    @Override public void rollbackSecondAcceptance(String roomId, String secondAcceptorId) {
        acceptance.rollbackSecondAcceptance(roomId, secondAcceptorId);
    }

    // ---- end ----

    @Override public Map<String, String> getCallHash(String roomId) {
        Map<Object, Object> raw = redis.opsForHash().entries(roomId);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }

    @Override public void cancelDurationTimer(String roomId) { timers.cancelTimeout(roomId); }
    @Override public void setStatus(String roomId, String status) { redis.opsForHash().put(roomId, "status", status); }

    @Override
    public Document pushEndedLog(String roomId) {
        return mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", id(roomId)),
                new Document("$push", new Document("logs", new Document("callStatus", "ended")
                        .append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
    }

    @Override
    public void notifyConsultantCallEnded(String consultantId) {
        Document consultant = findById(Collections.CONSULTANTS, consultantId);
        for (String token : fcmTokens(consultant)) {
            push.send("cons", token, "Call Ended", "Call ended", Map.of(),
                    null, true, Map.of());
        }
    }

    // ---- cancel ----

    @Override public void setStatusCancelled(String roomId) { setStatus(roomId, "cancelled"); }

    @Override
    public void consultantCancelledCall(String consultantId, String userId, String roomId) {
        Document sessions = new Document("canGoOffline", false).append("isChatLive", false)
                .append("isVoiceLive", false).append("isVideoLive", false).append("isGoLive", false);
        mongo.getCollection(Collections.CONSULTANTS).updateOne(new Document("_id", id(consultantId)),
                new Document("$set", new Document("sessionsStatus", sessions).append("updatedAt", new Date()))
                        .append("$inc", new Document("limit.flags.current", 1)));
        Document temp = new Document();
        if (userId != null && ObjectId.isValid(userId)) temp.append("userId", new ObjectId(userId));
        mongo.getCollection(Collections.FLAG_LOGS).insertOne(new Document("consultant_id", id(consultantId))
                .append("flag_type", "cancelled_call").append("waitlist_id", roomId)
                .append("temporary_data", temp).append("flag_status", "resolved")
                .append("flag_reason", "Consultant cancelled the call")
                .append("createdAt", new Date()).append("updatedAt", new Date()));
        releaseConsultantCall(consultantId, roomId);
    }

    @Override
    public Document pushCancelledLog(String roomId, String cancelledBy) {
        return mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", id(roomId)),
                new Document("$set", new Document("status", "canceled"))
                        .append("$push", new Document("logs", new Document("callStatus", "cancelled")
                                .append("cancelledBy", cancelledBy).append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    @Override public Document findConsultant(String id) { return findById(Collections.CONSULTANTS, id); }
    @Override public Document findUser(String id) { return findById(Collections.USERS, id); }

    @Override
    public void notifyUserMissedCall(Document consultant, String userName) {
        for (String token : fcmTokens(consultant)) {
            push.sendNotificationAndCons("cons", token,
                    (userName == null || userName.isBlank() ? "User" : userName) + " missed the call",
                    Map.of(), "Call Missed", "cons");
        }
    }

    @Override
    public CallCancelService.NextCaller callToNextConsultantAsItFree(String roomId) {
        // This special free-consultation continuation also creates a chat thread. It is intentionally
        // queued into the integration outbox so a failed chat-server call cannot corrupt call state.
        Document waitlist = findWaitlist(roomId);
        if (waitlist == null) return null;
        mongo.getCollection(Collections.JAVA_INTEGRATION_OUTBOX).updateOne(
                new Document("_id", "NEXT_FREE_CONSULTANT:" + roomId),
                new Document("$setOnInsert", new Document("type", "NEXT_FREE_CONSULTANT")
                        .append("payload", waitlist).append("status", "PENDING")
                        .append("owner", "java").append("attempts", 0)
                        .append("nextAttemptAt", new Date()).append("createdAt", new Date())),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
        return null;
    }

    @Override
    public void scheduleInitiateCall(CallCancelService.NextCaller next) {
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            CallLifecycleService lifecycle = lifecycleProvider.getIfAvailable();
            if (lifecycle != null) lifecycle.initiateCall(next.userId, next.consultantId,
                    next.roomId, next.callMode);
        });
    }

    // ---- extend + queue ----

    @Override public boolean extendTimer(String timerKey, long additionalSeconds) {
        String roomId = timerKey.endsWith(":duration")
                ? timerKey.substring(0, timerKey.length() - ":duration".length()) : timerKey;
        return timers.extendTimeout(roomId, additionalSeconds);
    }

    @Override public long ttlSeconds(String timerKey) {
        Long value = redis.getExpire(timerKey, TimeUnit.SECONDS);
        return value == null ? -2 : value;
    }

    @Override
    public List<Document> findWaitingPrivateCalls(String consultantId, List<String> activeModes) {
        return mongo.getCollection(Collections.WAITLISTS).find(new Document("consultant_id", consultantId)
                        .append("status", "waiting").append("used_for", "private_call")
                        .append("session_info.mode", new Document("$in", activeModes)))
                .sort(new Document("priority", -1).append("createdAt", 1)).into(new ArrayList<>());
    }

    private Document findById(String collection, String value) {
        if (value == null) return null;
        return mongo.getCollection(collection).find(new Document("_id", id(value))).first();
    }

    private Object id(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }
    private String consultantLock(String id) { return "lock:consultant-call:" + id; }

    private List<String> fcmTokens(Document actor) {
        if (actor == null || !(actor.get("device") instanceof Document device)
                || !(device.get("fcmToken") instanceof List<?> tokens)) return List.of();
        return tokens.stream().filter(t -> t != null && !String.valueOf(t).isBlank())
                .map(String::valueOf).toList();
    }
}
