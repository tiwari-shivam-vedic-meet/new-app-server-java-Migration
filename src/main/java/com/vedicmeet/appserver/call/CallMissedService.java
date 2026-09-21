package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.realtime.SocketEventPublisher;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Production-equivalent missed-call callback for Java-owned call timers. */
@Service
public class CallMissedService {

    public enum Outcome { MISSED_BOTH, USER_MISSED, CONSULTANT_MISSED, SESSION_SETTLED, INVALID_STATE }

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final SocketEventPublisher events;
    private final PushNotificationService push;
    private final CallCompletionService completion;
    private final ObjectProvider<CallQueueService> queueProvider;
    private final ObjectProvider<CallLifecycleService> lifecycleProvider;

    public CallMissedService(MongoTemplate mongo, StringRedisTemplate redis,
                             SocketEventPublisher events, PushNotificationService push,
                             CallCompletionService completion,
                             ObjectProvider<CallQueueService> queueProvider,
                             ObjectProvider<CallLifecycleService> lifecycleProvider) {
        this.mongo = mongo;
        this.redis = redis;
        this.events = events;
        this.push = push;
        this.completion = completion;
        this.queueProvider = queueProvider;
        this.lifecycleProvider = lifecycleProvider;
    }

    public Outcome handle(String roomId, String userId, String consultantId, String callType) {
        Map<String, String> call = callHash(roomId);
        String status = call.get("status");
        if ("initiated".equals(status)) {
            redis.opsForHash().put(roomId, "status", "missed");
            Date now = new Date();
            Document waitlist = findById(Collections.WAITLISTS, roomId);
            Date firstMissed = nestedDate(waitlist, "session_info", "firstMissedAt");
            if (firstMissed == null) firstMissed = now;
            Date nextRetry = Date.from(firstMissed.toInstant().plusSeconds(30 * 60L));
            mongo.getCollection(Collections.WAITLISTS).updateOne(
                    new Document("_id", id(roomId)).append("status", "initiated"),
                    new Document("$set", new Document("updatedAt", now).append("status", "missed")
                            .append("session_info.firstMissedAt", firstMissed)
                            .append("session_info.nextRetryAt", nextRetry)
                            .append("session_info.missedRetryCount", 0))
                            .append("$push", new Document("logs", new Document("actionBy", "both")
                                    .append("callStatus", "missed").append("timestamp", now.getTime()))));
            emitMissedBoth(roomId, userId, consultantId);
            markConsultantUnavailable(consultantId, userId, roomId, "missed_call", "Consultant missed the call");
            notifyUser(userId, "Call missed", "Your consultation call was not answered");
            releaseConsultantLock(consultantId, roomId);
            return Outcome.MISSED_BOTH;
        }

        if ("partially_accepted".equals(status)) {
            if ("session".equals(callType)) {
                redis.opsForHash().put(roomId, "status", "accepted");
                completion.complete(roomId, userId, consultantId, "completed", "cons");
                markConsultantUnavailable(consultantId, userId, roomId, "missed_call", "Consultant missed the call");
                return Outcome.SESSION_SETTLED;
            }

            redis.opsForHash().put(roomId, "status", "missed");
            String firstAcceptedBy = call.get("firstAcceptedBy");
            Date now = new Date();
            mongo.getCollection(Collections.WAITLISTS).updateOne(
                    new Document("_id", id(roomId))
                            .append("status", new Document("$in", List.of("initiated", "waiting"))),
                    new Document("$set", new Document("updatedAt", now).append("status", "canceled")
                            .append("session_info.firstMissedAt", now)
                            .append("session_info.nextRetryAt", Date.from(Instant.now().plusSeconds(30 * 60L)))
                            .append("session_info.missedRetryCount", 0))
                            .append("$push", new Document("logs", new Document("actionBy",
                                            consultantId.equals(firstAcceptedBy) ? userId : consultantId)
                                    .append("callStatus", "missed").append("timestamp", now.getTime()))));

            if (!consultantId.equals(firstAcceptedBy)) {
                markConsultantUnavailable(consultantId, userId, roomId, "missed_call", "Consultant missed the call");
                events.emitToRoom(userId, "call_missed", Map.of("roomId", roomId,
                        "message", "Consultant missed the call", "consultantId", consultantId));
                events.emitToRoom(consultantId, "app_action",
                        Map.of("action", "call_missed", "data", Map.of()));
                releaseConsultantLock(consultantId, roomId);
                return Outcome.CONSULTANT_MISSED;
            }

            notifyUser(userId, "Call missed", "You missed the consultation call");
            startNext(consultantId);
            releaseConsultantLock(consultantId, roomId);
            return Outcome.USER_MISSED;
        }
        return Outcome.INVALID_STATE;
    }

    private void emitMissedBoth(String roomId, String userId, String consultantId) {
        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("roomId", roomId);
        userPayload.put("message", "Call was not answered");
        userPayload.put("consultantId", consultantId);
        events.emitToRoom(userId, "call_missed", userPayload);
        events.emitToRoom(consultantId, "call_missed",
                Map.of("roomId", roomId, "message", "Call was not answered"));
        events.emitToRoom(consultantId, "app_action", Map.of("action", "call_missed", "data", Map.of()));
    }

    private void markConsultantUnavailable(String consultantId, String userId, String roomId,
                                           String flagType, String reason) {
        if (consultantId == null || !ObjectId.isValid(consultantId)) return;
        Document status = new Document("canGoOffline", false).append("isChatLive", false)
                .append("isVoiceLive", false).append("isVideoLive", false).append("isGoLive", false);
        mongo.getCollection(Collections.CONSULTANTS).updateOne(
                new Document("_id", new ObjectId(consultantId)),
                new Document("$set", new Document("sessionsStatus", status).append("updatedAt", new Date()))
                        .append("$inc", new Document("limit.flags.current", 1)));
        Document temporary = new Document();
        if (userId != null && ObjectId.isValid(userId)) temporary.append("userId", new ObjectId(userId));
        mongo.getCollection(Collections.FLAG_LOGS).insertOne(
                new Document("consultant_id", new ObjectId(consultantId)).append("flag_type", flagType)
                        .append("waitlist_id", roomId).append("temporary_data", temporary)
                        .append("flag_status", "resolved").append("flag_reason", reason)
                        .append("createdAt", new Date()).append("updatedAt", new Date()));
    }

    private void notifyUser(String userId, String title, String body) {
        Document user = findById(Collections.USERS, userId);
        for (String token : tokens(user)) {
            push.sendNotificationAndCons("user", token, body, Map.of(), title, "user");
        }
    }

    private void startNext(String consultantId) {
        Document consultant = findById(Collections.CONSULTANTS, consultantId);
        CallQueueService queue = queueProvider.getIfAvailable();
        CallLifecycleService lifecycle = lifecycleProvider.getIfAvailable();
        if (consultant == null || queue == null || lifecycle == null) return;
        CallQueueService.NextCaller next = queue.sendCallNotificationToNextUser(consultant);
        if (next != null) lifecycle.initiateCall(next.userId, next.consultantId, next.roomId, next.callMode);
    }

    private Map<String, String> callHash(String roomId) {
        Map<Object, Object> raw = redis.opsForHash().entries(roomId);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    private void releaseConsultantLock(String consultantId, String roomId) {
        if (consultantId == null) return;
        String key = "lock:consultant-call:" + consultantId;
        String owner = redis.opsForValue().get(key);
        if (roomId.equals(owner)) redis.delete(key);
    }

    private Document findById(String collection, String rawId) {
        if (rawId == null) return null;
        return mongo.getCollection(collection).find(new Document("_id", id(rawId))).first();
    }

    private Object id(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }
    private Date nestedDate(Document root, String parent, String child) {
        if (root == null || !(root.get(parent) instanceof Document d)) return null;
        return d.getDate(child);
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private List<String> tokens(Document actor) {
        if (actor == null) return List.of();
        Object value = doc(actor.get("device")).get("fcmToken");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(String::valueOf).toList();
    }
}
