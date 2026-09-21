package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. Safe two-phase port of {@code CallManager.acceptCall}
 * (current Node call.js L1475), with the production read/modify/write race removed:
 *
 *   - empty redis call hash → ghost no-op.
 *   - status 'initiated' (first accept) → redis 'partially_accepted' + emit 'call_partially_accepted'
 *     to both + waitlist "partially accepted by" log.
 *   - status 'partially_accepted':
 *       · type 'session_connect_again' → redis 'accepted' + startTime (reconnect).
 *       · a DIFFERENT acceptor → cancel missed timer, redis 'accepted', waitlist → 'progress'
 *         (timeLap.startTime + "started" log), **start the billing clock**
 *         ({@code scheduleTimer(roomId:duration, requested_time)}), then emit 'join_call_room' to the
 *         other party. Returns the join payload.
 *       · the SAME acceptor again → no-op.
 *   - status 'accepted' → rebuild the join payload for a re-join.
 *
 * Node quirk preserved: the phase-2 accessbilities treats coupon {@code 67d1...} as +audio, whereas the
 * 'accepted' re-join path treats the same coupon as a no-op — reproduced exactly.
 *
 * <p>The first/second accept decision is one atomic Redis operation supplied by
 * {@link CallAcceptStore#claimAcceptance}; all external effects happen after the winner is known.
 * NOTE: plain class (NOT a Spring {@code @Service}) — like {@link CallEndService},
 * {@link CallTimeoutSettlementService} and the socket dispatcher, this transition is unwired and is
 * instantiated only by its callers/tests, so it never participates in (or breaks) the bean graph until
 * the call layer is deliberately wired + human-reviewed.</p>
 */
public class CallAcceptService {

    public enum AcceptOutcome {
        GHOST_NO_REDIS, PARTIALLY_ACCEPTED, RECONNECTED, PROGRESS_STARTED, STATE_CONFLICT,
        NOOP_SAME_ACCEPTOR, ALREADY_ACCEPTED
    }

    private static final String COUPON_A = "67d195c835f0c73d7a488f7d";
    private static final String COUPON_B = "680218a0e65f3c49bcc45cc2";

    private final CallAcceptStore store;

    public CallAcceptService(CallAcceptStore store) {
        this.store = store;
    }

    public AcceptOutcome acceptCall(String roomId, String userId, String consultantId, String type) {
        String currentAcceptor = acceptor(userId, consultantId);
        CallAcceptStore.AcceptanceClaim claim = store.claimAcceptance(
                roomId, currentAcceptor, "session_connect_again".equals(type), System.currentTimeMillis());
        if (claim.type == CallAcceptStore.ClaimType.GHOST) {
            return AcceptOutcome.GHOST_NO_REDIS;
        }

        store.markAccepted(userId, consultantId);

        Map<String, String> callData = claim.callData;
        String storedUserId = callData.get("userId");
        String storedConsultantId = callData.get("consultantId");

        if (claim.type == CallAcceptStore.ClaimType.FIRST) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("roomId", roomId);
            payload.put("acceptedBy", currentAcceptor);
            store.emit(storedUserId, "call_partially_accepted", payload);
            store.emit(storedConsultantId, "call_partially_accepted", payload);

            store.pushWaitlistLog(roomId, userId != null ? "user" : "consultant", "partially accepted by");
            return AcceptOutcome.PARTIALLY_ACCEPTED;
        }

        if (claim.type == CallAcceptStore.ClaimType.RECONNECTED) {
            return AcceptOutcome.RECONNECTED;
        }

        if (claim.type == CallAcceptStore.ClaimType.SECOND) {
            try {
                store.cancelMissedTimer(roomId);
                Document entry = store.progressWaitlist(roomId);
                if (entry == null) {
                    store.rollbackSecondAcceptance(roomId, currentAcceptor);
                    return AcceptOutcome.STATE_CONFLICT;
                }
                long duration = (long) num(entry.get("requested_time"));
                store.startDurationTimer(roomId, duration);

                List<String> accessbilities = phase2Accessbilities(entry);
                Map<String, Object> payload = joinPayload(entry, accessbilities);

                String needToSendJoinRoomTo = currentAcceptor.equals(storedUserId) ? "user" : "consultant";
                if ("consultant".equals(needToSendJoinRoomTo)) store.emitJoinCallRoom(storedUserId, payload);
                if ("user".equals(needToSendJoinRoomTo)) store.emitJoinCallRoom(storedConsultantId, payload);

                return AcceptOutcome.PROGRESS_STARTED;
            } catch (RuntimeException e) {
                store.rollbackSecondAcceptance(roomId, currentAcceptor);
                throw e;
            }
        }

        if (claim.type == CallAcceptStore.ClaimType.SAME_ACCEPTOR
                || claim.type == CallAcceptStore.ClaimType.INVALID_STATE) {
            return AcceptOutcome.NOOP_SAME_ACCEPTOR;
        }

        if (claim.type == CallAcceptStore.ClaimType.ALREADY_ACCEPTED) {
            Document entry = store.findWaitlist(roomId);
            List<String> accessbilities = acceptedAccessbilities(entry);
            Map<String, Object> payload = joinPayload(entry, accessbilities);
            store.emitJoinCallRoom(storedUserId, payload);
            return AcceptOutcome.ALREADY_ACCEPTED;
        }

        return AcceptOutcome.NOOP_SAME_ACCEPTOR;
    }

    private String acceptor(String userId, String consultantId) {
        return userId != null ? userId : (consultantId != null ? consultantId : "unknown");
    }

    /** call.js L1630-1642: 67d1 → +audio; session/video → +audio,video; audio/680.../chat → +audio. */
    private List<String> phase2Accessbilities(Document e) {
        List<String> a = new ArrayList<>();
        a.add("chat");
        String coupon = couponId(e);
        String mode = sessionMode(e);
        String usedFor = e == null ? null : e.getString("used_for");
        if (COUPON_A.equals(coupon)) {
            a.add("audio");
        } else if ("session".equals(usedFor) || "video".equals(mode)) {
            a.add("audio");
            a.add("video");
        } else if ("audio".equals(mode) || COUPON_B.equals(coupon) || "chat".equals(mode)) {
            a.add("audio");
        }
        return a;
    }

    /** call.js L1683-1693: 67d1 → NO-OP (empty) — the deliberate difference from phase-2. */
    private List<String> acceptedAccessbilities(Document e) {
        List<String> a = new ArrayList<>();
        a.add("chat");
        String coupon = couponId(e);
        String mode = sessionMode(e);
        String usedFor = e == null ? null : e.getString("used_for");
        if (COUPON_A.equals(coupon)) {
            // no-op (Node intentionally empty here)
        } else if ("session".equals(usedFor) || "video".equals(mode)) {
            a.add("audio");
            a.add("video");
        } else if ("audio".equals(mode) || COUPON_B.equals(coupon) || "chat".equals(mode)) {
            a.add("audio");
        }
        return a;
    }

    private Map<String, Object> joinPayload(Document e, List<String> accessbilities) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("waitlistId", e == null ? null : String.valueOf(e.get("_id")));
        p.put("roomId", e == null ? null : e.get("threadId"));
        p.put("channel", sessionMode(e));
        p.put("accessbilities", accessbilities);
        return p;
    }

    private String couponId(Document e) {
        if (e != null && e.get("coupon") instanceof Document) {
            Object id = ((Document) e.get("coupon")).get("_id");
            return id == null ? null : id.toString();
        }
        return null;
    }

    private String sessionMode(Document e) {
        if (e != null && e.get("session_info") instanceof Document) {
            Object m = ((Document) e.get("session_info")).get("mode");
            return m == null ? null : m.toString();
        }
        return null;
    }

    private double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception ex) { return 0; }
    }
}
