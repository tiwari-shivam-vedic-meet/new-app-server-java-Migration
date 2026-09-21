package com.vedicmeet.appserver.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Ports the pending-fixed-session and 30-second next-user branches at Node call.js L2042-2088. */
@Service
public class CallContinuationService {

    private static final Logger log = LoggerFactory.getLogger(CallContinuationService.class);
    private static final String SESSION_CACHE_PREFIX = "ncache:sessions:";

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final JavaCallTimerService timers;
    private final CallQueueService queue;
    private final CallLifecycleService lifecycle;

    public CallContinuationService(MongoTemplate mongo, StringRedisTemplate redis, ObjectMapper mapper,
                                   JavaCallTimerService timers, CallQueueService queue,
                                   CallLifecycleService lifecycle) {
        this.mongo = mongo;
        this.redis = redis;
        this.mapper = mapper;
        this.timers = timers;
        this.queue = queue;
        this.lifecycle = lifecycle;
    }

    public void afterCall(Document consultant, String completedRoomId, String callEndedBy) {
        if (consultant == null || consultant.get("_id") == null) return;
        String consultantId = String.valueOf(consultant.get("_id"));

        String pendingSessionId = pendingSessionId(consultantId);
        if (pendingSessionId != null) {
            redis.delete(SESSION_CACHE_PREFIX + consultantId);
            Document pending = findById(Collections.WAITLISTS, pendingSessionId);
            if (pending != null) {
                lifecycle.initiateCall(String.valueOf(pending.get("user_id")),
                        String.valueOf(pending.get("consultant_id")),
                        String.valueOf(pending.get("_id")), "session");
            }
            return;
        }

        if ("system".equals(callEndedBy)) {
            timers.scheduleNextUser(completedRoomId, consultantId, 30);
        } else {
            initiateNext(consultant);
        }
    }

    /** Worker callback after the user's 30-second reconnect opportunity. */
    public void afterReconnectWindow(String consultantId) {
        Document busy = mongo.getCollection(Collections.WAITLISTS).find(
                new Document("consultant_id", consultantId)
                        .append("status", new Document("$in", List.of("progress", "initiated"))))
                .first();
        if (busy != null) return;
        Document consultant = findById(Collections.CONSULTANTS, consultantId);
        if (consultant != null) initiateNext(consultant);
    }

    private void initiateNext(Document consultant) {
        CallQueueService.NextCaller next = queue.sendCallNotificationToNextUser(consultant);
        if (next == null) return;
        try {
            lifecycle.initiateCall(next.userId, next.consultantId, next.roomId, next.callMode);
        } catch (RuntimeException error) {
            log.warn("next call initiation failed roomId={}", next.roomId, error);
            throw error;
        }
    }

    private String pendingSessionId(String consultantId) {
        String raw = redis.opsForValue().get(SESSION_CACHE_PREFIX + consultantId);
        if (raw == null || raw.isBlank()) return null;
        try {
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                return mapper.readValue(raw, String.class);
            }
            return raw;
        } catch (Exception malformed) {
            log.warn("invalid pending-session cache consultantId={}", consultantId);
            return null;
        }
    }

    private Document findById(String collection, String rawId) {
        Object id = ObjectId.isValid(rawId) ? new ObjectId(rawId) : rawId;
        return mongo.getCollection(collection).find(new Document("_id", id)).first();
    }
}
