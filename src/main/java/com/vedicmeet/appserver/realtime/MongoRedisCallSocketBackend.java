package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.vedicmeet.appserver.call.CallAcceptService;
import com.vedicmeet.appserver.call.CallCancelService;
import com.vedicmeet.appserver.call.CallCompletionService;
import com.vedicmeet.appserver.call.CallExtendService;
import com.vedicmeet.appserver.call.CallLifecycleService;

/**
 * Read-side implementation used by the Java /app Socket.IO namespace.
 *
 * <p>Mutating call operations deliberately fail closed. The Java service must not accept ownership
 * of a Node-created call until its durable timer, settlement, notification, and multi-instance socket
 * transports are all installed. Read handlers can be contract-tested without creating split brain.</p>
 */
@Component
public class MongoRedisCallSocketBackend implements CallSocketBackend {

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final SocketEventPublisher events;
    private final CallAcceptService accept;
    private final CallCancelService cancel;
    private final CallCompletionService completion;
    private final CallExtendService extend;
    private final CallLifecycleService lifecycle;

    public MongoRedisCallSocketBackend(MongoTemplate mongo, StringRedisTemplate redis,
                                       SocketEventPublisher events, CallAcceptService accept,
                                       CallCancelService cancel, CallCompletionService completion,
                                       CallExtendService extend, CallLifecycleService lifecycle) {
        this.mongo = mongo;
        this.redis = redis;
        this.events = events;
        this.accept = accept;
        this.cancel = cancel;
        this.completion = completion;
        this.extend = extend;
        this.lifecycle = lifecycle;
    }

    @Override public void acceptCall(String roomId, String userId, String consultantId, String type) {
        accept.acceptCall(roomId, userId, consultantId, type);
    }
    @Override public void cancelCall(String roomId, String userId, String consultantId) {
        cancel.cancelCall(roomId, userId, consultantId);
    }
    @Override public void hsetStatus(String roomId, String status) {
        redis.opsForHash().put(roomId, "status", status);
    }
    @Override public Object handleCallTimeout(String roomId, String userId, String consultantId,
                                               String callStatus, String callEndedBy) {
        return completion.complete(roomId, userId, consultantId, callStatus, callEndedBy);
    }
    @Override public void initiateSessionCallToConsultant(Document waitlist) {
        Document session = waitlist == null ? null : waitlist.get("session_info", Document.class);
        Document meta = session == null ? null : session.get("sessionMeta", Document.class);
        String mode = meta == null || meta.getString("mode") == null ? "chat" : meta.getString("mode");
        lifecycle.initiateCall(String.valueOf(waitlist.get("user_id")),
                String.valueOf(waitlist.get("consultant_id")), String.valueOf(waitlist.get("_id")), mode);
    }
    @Override public boolean extendCallDuration(String roomId, long additionalSeconds) {
        return extend.extendCall(roomId, additionalSeconds);
    }

    @Override
    public Document findProgressSessionForConsultant(String roomId, String consultantId) {
        return mongo.getCollection(Collections.WAITLISTS).find(idFilter(roomId)
                .append("used_for", "session").append("status", "progress")
                .append("consultant_id", consultantId)).first();
    }

    @Override
    public Document findProgressWaitlistForActor(String roomId, String userType, String actorId) {
        Document filter = idFilter(roomId).append("status", "progress");
        if (CallSocketDispatcher.USER.equals(userType)) filter.append("user_id", actorId);
        if (CallSocketDispatcher.CONSULTANT.equals(userType)) filter.append("consultant_id", actorId);
        return mongo.getCollection(Collections.WAITLISTS).find(filter).first();
    }

    @Override
    public Map<String, String> getCallData(String roomId) {
        Map<Object, Object> raw = redis.opsForHash().entries(roomId);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }

    @Override
    public boolean isWaitlistCompleted(String roomId) {
        return mongo.getCollection(Collections.WAITLISTS)
                .countDocuments(idFilter(roomId).append("status", "completed")) > 0;
    }

    @Override
    public void deleteCallData(String roomId) {
        redis.delete(roomId);
    }

    @Override
    public Document findCompletedWaitlistTime(String roomId) {
        return mongo.getCollection(Collections.WAITLISTS)
                .find(idFilter(roomId).append("status", "completed"))
                .projection(new Document("timeLap", 1).append("used_for", 1)).first();
    }

    @Override
    public Document findWaitlistTime(String roomId) {
        return mongo.getCollection(Collections.WAITLISTS).find(idFilter(roomId))
                .projection(new Document("timeLap", 1)).first();
    }

    @Override
    public boolean isTimerActive(String timerKey) {
        return Boolean.TRUE.equals(redis.hasKey(timerKey));
    }

    @Override
    public long getRemainingTime(String timerKey) {
        Long ttl = redis.getExpire(timerKey, TimeUnit.SECONDS);
        return ttl == null ? -2L : ttl;
    }

    @Override public void joinRoom(String roomId) { /* handled on the authenticated client */ }
    @Override public void leaveRoom(String roomId) { /* handled on the authenticated client */ }
    @Override public void emit(String event, Object payload) { events.emitToCurrent(event, payload); }

    private Document idFilter(String id) {
        Object value = id != null && ObjectId.isValid(id) ? new ObjectId(id) : id;
        return new Document("_id", value);
    }

}
