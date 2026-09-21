package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of the batch consultant-status reads on the waitlist class
 * (utils/classes/waitlist.js getConsultantStatusBatch L449 + _buildConsultantStatusResponse
 * L417 + _normalizeConsultantId L410 + _emptyConsultantStatusResponse L445).
 *
 * Used by consultantList to attach `_consultantWaitlistStatus`. Busy state is derived
 * from the Redis billing-clock via {@link TimerReadService} (read-only). Waitlist queue
 * is a `waitlists` aggregation. All ordering/shape preserved to match Node byte-for-byte.
 */
@Service
public class WaitlistStatusService {

    private final MongoTemplate mongo;
    private final TimerReadService timers;

    public WaitlistStatusService(MongoTemplate mongo, TimerReadService timers) {
        this.mongo = mongo;
        this.timers = timers;
    }

    /** getConsultantStatusBatch(consultantIds) -> { <consultantId>: statusResponse }. */
    public Map<String, Object> getConsultantStatusBatch(List<?> consultantIds) {
        // ids = [...new Set(consultantIds.map(normalize).filter(Boolean))]
        LinkedHashSet<String> idSet = new LinkedHashSet<>();
        if (consultantIds != null) {
            for (Object raw : consultantIds) {
                String id = normalizeConsultantId(raw);
                if (id != null && !id.isEmpty()) idSet.add(id);
            }
        }
        List<String> ids = new ArrayList<>(idSet);

        Map<String, Object> result = new LinkedHashMap<>();
        for (String id : ids) result.put(id, emptyConsultantStatusResponse());
        if (ids.isEmpty()) return result;

        // progress sessions
        List<Document> progressSessions = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("consultant_id", new Document("$in", ids)).append("status", "progress"))
                .into(new ArrayList<>());

        // waiting/missed queue aggregation
        List<Document> waitlistAggregation = mongo.getCollection(Collections.WAITLISTS).aggregate(Arrays.asList(
                new Document("$match", new Document("consultant_id", new Document("$in", ids))
                        .append("status", new Document("$in", Arrays.asList("waiting", "missed")))
                        .append("used_for", new Document("$in", Arrays.asList("private_call", "session")))),
                new Document("$sort", new Document("priority", -1).append("createdAt", -1)),
                new Document("$group", new Document("_id", "$consultant_id")
                        .append("entries", new Document("$push", "$$ROOT"))
                        .append("totalRequestedTime", new Document("$sum", "$requested_time"))),
                new Document("$project", new Document("entries", 1).append("totalRequestedTime", 1))
        )).into(new ArrayList<>());

        // busy state (first progress session per consultant with an active timer)
        Map<String, Document> busyStateByConsultant = new LinkedHashMap<>();
        for (Document session : progressSessions) {
            String consultantKey = normalizeConsultantId(session.get("consultant_id"));
            if (consultantKey == null || busyStateByConsultant.containsKey(consultantKey)) continue;
            String roomId = session.getObjectId("_id").toHexString() + ":duration";
            boolean active = timers.isTimerActive(roomId);
            long remainingTime = active ? timers.getRemainingTime(roomId) : 0;
            if (active && remainingTime > 0) {
                busyStateByConsultant.put(consultantKey, new Document("isBusy", true)
                        .append("currentSession", session)
                        .append("currentSessionRemainingTime", remainingTime));
            }
        }

        // waitlist queue by consultant
        Map<String, Document> waitlistByConsultant = new LinkedHashMap<>();
        for (Document w : waitlistAggregation) {
            String consultantKey = normalizeConsultantId(w.get("_id"));
            if (consultantKey == null) continue;
            List<Document> entries = w.getList("entries", Document.class, new ArrayList<>());
            List<Document> mapped = new ArrayList<>();
            for (Document entry : entries) {
                mapped.add(new Document("userId", entry.get("user_id"))
                        .append("requestedTime", entry.get("requested_time")));
            }
            Object total = w.get("totalRequestedTime");
            waitlistByConsultant.put(consultantKey, new Document("waitlistEntries", mapped)
                    .append("totalWaitlistTime", total == null ? 0 : total));
        }

        for (String id : ids) {
            Document busy = busyStateByConsultant.get(id);
            Document waitlist = waitlistByConsultant.get(id);
            boolean isBusy = busy != null && Boolean.TRUE.equals(busy.getBoolean("isBusy"));
            Object currentSession = busy == null ? null : busy.get("currentSession");
            Number currentRemaining = busy == null ? 0 : (Number) busy.getOrDefault("currentSessionRemainingTime", 0);
            List<Document> waitlistEntries = waitlist == null
                    ? new ArrayList<>() : waitlist.getList("waitlistEntries", Document.class, new ArrayList<>());
            Number totalWaitlistTime = waitlist == null ? 0 : (Number) waitlist.getOrDefault("totalWaitlistTime", 0);

            result.put(id, buildConsultantStatusResponse(isBusy, currentSession,
                    currentRemaining, waitlistEntries, totalWaitlistTime));
        }
        return result;
    }

    private String normalizeConsultantId(Object consultantId) {
        return consultantId == null ? null : consultantId.toString();
    }

    // ---- Reads used by cons/popular (waitlist.js getConsultantWaitlist L167, getProgressingWaitlist L672-704) ----

    /**
     * getConsultantWaitlist(consultantId) — mode defaults to null, status defaults to ['waiting'].
     * Returns the raw aggregation array (0 or 1 element with totalRequestedTime + entries).
     */
    public List<Document> getConsultantWaitlist(String consultantId) {
        return mongo.getCollection(Collections.WAITLISTS).aggregate(Arrays.asList(
                new Document("$match", new Document("consultant_id", consultantId)
                        .append("status", new Document("$in", Arrays.asList("waiting")))
                        .append("used_for", "private_call")),
                new Document("$sort", new Document("priority", -1).append("createdAt", -1)),
                new Document("$group", new Document("_id", null)
                        .append("totalRequestedTime", new Document("$sum", "$requested_time"))
                        .append("entries", new Document("$push", "$$ROOT")))
        )).into(new ArrayList<>());
    }

    /**
     * getProgressingWaitlist(null, consultantId) — else branch (L672-704). findOne the consultant's
     * progress session; if the Redis billing clock is still active, returns { remainingTime, waitlist }
     * (waitlist enriched with accessbilities), otherwise null. Coupon ids and session mode drive
     * accessbilities exactly as in Node.
     */
    public Document getProgressingWaitlistByConsultant(String consultantId) {
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("consultant_id", consultantId).append("status", "progress"))
                .first();
        if (waitlist == null) return null;

        String roomId = waitlist.getObjectId("_id").toHexString() + ":duration";
        boolean isActive = timers.isTimerActive(roomId);
        long remainingTime = timers.getRemainingTime(roomId);
        if (!isActive) return null;

        List<String> accessbilities = new ArrayList<>();
        accessbilities.add("chat");
        String couponId = couponIdOf(waitlist);
        String mode = sessionMode(waitlist);
        String usedFor = waitlist.getString("used_for");
        if ("67d195c835f0c73d7a488f7d".equals(couponId)) {
            // no-op branch (Node intentionally empty)
        } else if ("session".equals(usedFor) || "video".equals(mode)) {
            accessbilities.add("audio");
            accessbilities.add("video");
        } else if ("audio".equals(mode) || "680218a0e65f3c49bcc45cc2".equals(couponId) || "chat".equals(mode)) {
            accessbilities.add("audio");
        }

        Document enriched = new Document(waitlist);
        enriched.append("accessbilities", accessbilities);
        return new Document("remainingTime", remainingTime).append("waitlist", enriched);
    }

    private String couponIdOf(Document waitlist) {
        Object coupon = waitlist.get("coupon");
        if (coupon instanceof Document) {
            Object id = ((Document) coupon).get("_id");
            return id == null ? null : id.toString();
        }
        return null;
    }

    private String sessionMode(Document waitlist) {
        Object info = waitlist.get("session_info");
        if (info instanceof Document) {
            Object mode = ((Document) info).get("mode");
            return mode == null ? null : mode.toString();
        }
        return null;
    }

    private Document emptyConsultantStatusResponse() {
        return buildConsultantStatusResponse(false, null, 0, new ArrayList<>(), 0);
    }

    private Document buildConsultantStatusResponse(boolean isBusy, Object currentSession,
                                                   Number currentSessionRemainingTime,
                                                   List<Document> waitlistEntries, Number totalWaitlistTime) {
        double remaining = currentSessionRemainingTime == null ? 0 : currentSessionRemainingTime.doubleValue();
        double totalWait = totalWaitlistTime == null ? 0 : totalWaitlistTime.doubleValue();
        double totalEstimatedTime = remaining + totalWait;
        int queueLen = waitlistEntries == null ? 0 : waitlistEntries.size();

        String sessionType = null;
        if (currentSession instanceof Document) {
            Object uf = ((Document) currentSession).get("used_for");
            sessionType = uf == null ? null : uf.toString();
        }

        Document detailed = new Document("hasActiveSession", isBusy)
                .append("sessionType", sessionType)
                .append("waitlistQueueLength", queueLen)
                .append("estimatedWaitTime", totalEstimatedTime)
                .append("currentSessionTimeRemaining", currentSessionRemainingTime);

        return new Document("isBusy", isBusy)
                .append("currentSession", currentSession)
                .append("currentSessionRemainingTime", currentSessionRemainingTime)
                .append("waitlistCount", queueLen)
                .append("waitlistEntries", waitlistEntries)
                .append("totalWaitlistTime", totalWaitlistTime)
                .append("totalEstimatedTime", totalEstimatedTime)
                .append("state", isBusy ? "busy" : "available")
                .append("detailedState", detailed);
    }
}
