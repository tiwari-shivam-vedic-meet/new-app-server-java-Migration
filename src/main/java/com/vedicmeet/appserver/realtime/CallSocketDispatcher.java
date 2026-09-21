package com.vedicmeet.appserver.realtime;

import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. FAITHFUL port of the app-namespace CALL socket handler bodies
 * (Node sockets/namespaces/app.js L321-473), migrated as-is. This is the routing layer that
 * resolves the actor from the authenticated handshake ({@code socket.userType}/{@code socket.user}),
 * runs the same guard queries, and delegates to {@code CallManager} via {@link CallSocketBackend}.
 *
 * <p>No logic changes (team decision 2026-08-31): actor→null mapping, the cancel_call session branch,
 * and the end_call session-consultant retry-then-force-accept quirk are reproduced exactly. The
 * Socket.IO transport that would invoke these stays disabled ({@code vedicmeet.socket.enabled=false}).</p>
 */
@Component
public class CallSocketDispatcher {

    public static final String USER = "user";
    public static final String CONSULTANT = "consultant";

    public enum CancelResult { SESSION_CONSULTANT_TIMEOUT, SESSION_NOT_FOUND_NOOP, PLAIN_CANCEL, ERROR }
    public enum EndResult { WAITLIST_NOT_FOUND, SESSION_CONSULTANT_RETRY, ENDED, ERROR }

    /** cb payload for end_call ({success,message,data}). */
    public static final class Ack {
        public final boolean success;
        public final String message;
        public final Object data;
        Ack(boolean success, String message, Object data) { this.success = success; this.message = message; this.data = data; }
    }

    private final CallSocketBackend backend;

    public CallSocketDispatcher(CallSocketBackend backend) {
        this.backend = backend;
    }

    /** accept_call (L358): map the actor to user/consultant, delegate to acceptCall. */
    public void acceptCall(String userType, String actorId, String roomId, String type) {
        try {
            String userId = USER.equals(userType) ? actorId : null;
            String consultantId = CONSULTANT.equals(userType) ? actorId : null;
            backend.acceptCall(roomId, userId, consultantId, type);
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(roomId, "Failed to accept call", e));
        }
    }

    /** cancel_call (L321): consultant ending a live SESSION → force settlement; otherwise plain cancel. */
    public CancelResult cancelCall(String userType, String actorId, String roomId, boolean isCallSessionType) {
        try {
            if (isCallSessionType && CONSULTANT.equals(userType)) {
                Document w = backend.findProgressSessionForConsultant(roomId, actorId);
                if (w == null) {
                    return CancelResult.SESSION_NOT_FOUND_NOOP;
                }
                backend.hsetStatus(roomId, "accepted");
                backend.handleCallTimeout(roomId, str(w.get("user_id")), str(w.get("consultant_id")), null, "cons");
                return CancelResult.SESSION_CONSULTANT_TIMEOUT;
            }
            String userId = USER.equals(userType) ? actorId : null;
            String consultantId = CONSULTANT.equals(userType) ? actorId : null;
            backend.cancelCall(roomId, userId, consultantId);
            return CancelResult.PLAIN_CANCEL;
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(roomId, "Failed to cancel call", e));
            return CancelResult.ERROR;
        }
    }

    /** end_call (L376): actor-scoped guard, the session-consultant retry quirk, then handleCallTimeout. */
    public Ack endCall(String userType, String actorId, String waitlistId, double elapsedTime) {
        try {
            Document w = backend.findProgressWaitlistForActor(waitlistId, userType, actorId);
            if (w == null) {
                return new Ack(false, "Waitlist not found", null);
            }

            if ("session".equals(w.getString("used_for")) && CONSULTANT.equals(userType)
                    && (bookedSeconds(w) > elapsedTime)) {
                boolean consultantCancelled = isConsultantCancelled(w);
                if (!consultantCancelled) {
                    backend.initiateSessionCallToConsultant(w);
                    return new Ack(false, "Consultant has cancelled the session", null);
                }
                backend.hsetStatus(str(w.get("_id")), "accepted");
            }

            if (isExotel(w)) {
                backend.emit("call_error", callError(waitlistId, "Call cannot be ended!", null));
                return new Ack(false, "Call cannot be ended!", null);
            }

            Object callData = backend.handleCallTimeout(
                    waitlistId, str(w.get("user_id")), str(w.get("consultant_id")), "ended", userType);
            return new Ack(true, "Call ended", callData);
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(waitlistId, "Failed to end call", e));
            return new Ack(false, "Failed to end call", null);
        }
    }

    /** verify_end_call (Node app.js L576): true for completed or an absent/empty call hash. */
    public boolean verifyEndCall(String roomId) {
        try {
            Map<String, String> callData = backend.getCallData(roomId);
            return callData == null || callData.isEmpty() || "completed".equals(callData.get("status"));
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(roomId, "Failed to end call", e));
            return false;
        }
    }

    /** check_call_status (Node app.js L421): completed waitlist wins; otherwise Redis accepted wins. */
    public boolean checkCallStatus(String roomId) {
        try {
            Map<String, String> callData = backend.getCallData(roomId);
            if (backend.isWaitlistCompleted(roomId)) {
                backend.deleteCallData(roomId);
                return true;
            }
            return callData != null && "accepted".equals(callData.get("status"));
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(roomId, "Failed to accept call", e));
            return false;
        }
    }

    /**
     * room_remaining_time (Node app.js L215). The returned map is the exact callback shape:
     * {@code {time}} or {@code {time, elapsedTime}}.
     */
    public Map<String, Long> roomRemainingTime(String roomId, String callStatus) {
        try {
            if ("completed".equals(callStatus)) {
                return Collections.singletonMap("time", completedRoomRemainingTime(
                        backend.findCompletedWaitlistTime(roomId), Instant.now()));
            }

            String timerKey = roomId + ":duration";
            long remaining = backend.getRemainingTime(timerKey);
            if (!backend.isTimerActive(timerKey)) {
                return Collections.singletonMap("time", -1L);
            }

            Document waitlist = backend.findWaitlistTime(roomId);
            Date started = nestedDate(waitlist, "timeLap", "startTime");
            long elapsed = started == null ? 0L
                    : Math.max(0L, (System.currentTimeMillis() - started.getTime()) / 1_000L);
            Map<String, Long> result = new LinkedHashMap<>();
            result.put("time", remaining);
            result.put("elapsedTime", elapsed);
            return result;
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(roomId, "Failed to get room remaining time", e));
            return Collections.singletonMap("time", -1L);
        }
    }

    /** extend_call (L447): delegate; emit extend_call_failed when the clock could not be extended. */
    public void extendCall(String roomId, long additionalSeconds) {
        try {
            boolean success = backend.extendCallDuration(roomId, additionalSeconds);
            if (!success) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("roomId", roomId);
                p.put("message", "Failed to extend call duration");
                backend.emit("extend_call_failed", p);
            }
        } catch (RuntimeException e) {
            backend.emit("call_error", callError(roomId, "Failed to extend call", e));
        }
    }

    public void joinRoom(String roomId) { backend.joinRoom(roomId); }

    public void leaveRoom(String roomId) { backend.leaveRoom(roomId); }

    private double bookedSeconds(Document w) {
        Document si = (Document) w.get("session_info");
        if (si == null) return 0;
        Document meta = (Document) si.get("sessionMeta");
        if (meta == null) return 0;
        Object m = meta.get("sessionTimeInMinutes");
        return (m instanceof Number ? ((Number) m).doubleValue() : 0) * 60;
    }

    private boolean isConsultantCancelled(Document w) {
        Document si = (Document) w.get("session_info");
        if (si == null) return false;
        Document meta = (Document) si.get("sessionMeta");
        if (meta == null) return false;
        return Boolean.TRUE.equals(meta.get("isConsultantCancelled"));
    }

    private boolean isExotel(Document w) {
        Document si = w == null ? null : w.get("session_info", Document.class);
        return si != null && "exotel".equals(si.getString("callModeCourier"));
    }

    static long completedRoomRemainingTime(Document waitlist, Instant nowInstant) {
        if (waitlist == null) return -2L;
        Date start = nestedDate(waitlist, "timeLap", "startTime");
        Date end = nestedDate(waitlist, "timeLap", "endTime");
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = nowInstant.atZone(zone);

        if ("query".equals(waitlist.getString("used_for"))) {
            if (start == null) return -2L;
            ZonedDateTime started = start.toInstant().atZone(zone);
            if (!started.toLocalDate().equals(now.toLocalDate())) return -2L;
            return Math.max(0L, java.time.Duration.between(now, now.toLocalDate().plusDays(1)
                    .atStartOfDay(zone)).getSeconds());
        }

        if (end == null) return -2L;
        Instant allowedUntil = end.toInstant().plusSeconds(4 * 60 * 60L);
        if (!allowedUntil.isAfter(nowInstant)) return -2L;
        return Math.max(0L, java.time.Duration.between(nowInstant, allowedUntil).getSeconds());
    }

    private static Date nestedDate(Document root, String objectKey, String dateKey) {
        if (root == null) return null;
        Document nested = root.get(objectKey, Document.class);
        return nested == null ? null : nested.getDate(dateKey);
    }

    private Map<String, Object> callError(String roomKey, String message, RuntimeException e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("roomId", roomKey);
        p.put("message", message);
        p.put("error", e == null ? message : e.getMessage());
        return p;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
