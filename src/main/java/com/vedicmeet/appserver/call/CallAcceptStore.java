package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.Map;

/**
 * DB / redis / socket / timer effects for the two-phase {@code acceptCall} (Node call.js L1494),
 * behind a port so the state transitions are unit-testable without Redis / Socket.IO.
 */
public interface CallAcceptStore {

    enum ClaimType { GHOST, FIRST, SECOND, RECONNECTED, SAME_ACCEPTOR, ALREADY_ACCEPTED, INVALID_STATE }

    /** Result of the single atomic Redis state transition. */
    final class AcceptanceClaim {
        public final ClaimType type;
        public final Map<String, String> callData;

        public AcceptanceClaim(ClaimType type, Map<String, String> callData) {
            this.type = type;
            this.callData = callData == null ? Map.of() : Map.copyOf(callData);
        }
    }

    /**
     * Atomically claim the first/second acceptance in Redis. Implementations must not perform a
     * separate HGETALL followed by HSET: two simultaneous users can otherwise both become the first
     * acceptor and leave the call stuck in {@code partially_accepted}.
     */
    AcceptanceClaim claimAcceptance(String roomId, String acceptorId, boolean reconnect, long acceptedAtMillis);

    /** callInitated.findOneAndUpdate — mark isAcceptedByUser / isAcceptedByConsultant. */
    void markAccepted(String userId, String consultantId);

    /** namespace('app').to(room).emit(event, payload). */
    void emit(String room, String event, Object payload);

    /** waitlist $push log { actionBy, callStatus, timestamp }. */
    void pushWaitlistLog(String roomId, String actionBy, String callStatus);

    /** cancelTimer(getTimerKey(roomId)) — the missed-call timer. */
    void cancelMissedTimer(String roomId);

    /**
     * waitlist.findOneAndUpdate → status 'progress' + timeLap.startTime + log 'started', {new:true}.
     * Returns the updated entry (requested_time, threadId, session_info, coupon, used_for).
     */
    Document progressWaitlist(String roomId);

    /** scheduleTimer("&lt;roomId&gt;:duration", durationSeconds, CALL_TIMEOUT payload) — the billing clock. */
    void startDurationTimer(String roomId, long durationSeconds);

    /** waitlist.findOne({_id: roomId}) — for the already-'accepted' re-join path. */
    Document findWaitlist(String roomId);

    /** emitWithAckRetry(to, 'join_call_room', payload). */
    void emitJoinCallRoom(String toRoom, Map<String, Object> payload);

    /**
     * Best-effort compensation when Redis claimed SECOND but the guarded Mongo progress transition
     * could not be completed. Implementations must only revert the same second-acceptor claim.
     */
    void rollbackSecondAcceptance(String roomId, String secondAcceptorId);
}
