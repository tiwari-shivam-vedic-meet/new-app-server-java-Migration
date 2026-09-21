package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.springframework.stereotype.Service;

/**
 * ⚠⚠ SHADOW-ONLY / REAL-TIME STATE — HUMAN REVIEW + HARNESS REQUIRED BEFORE ENABLING ⚠⚠
 *
 * Faithful port of the validation gates of Node {@code CallManager.initiateCall}
 * (utils/classes/call.js L381). Reproduces the exact transition ORDER and cancel/miss log strings the
 * Jest suite pins (tests-integration/call/call-lifecycle.test.js "initiateCall — validation gates"):
 *
 *   1. user OR consultant not found  → waitlist 'canceled' ("cancelled - <who> not found"); callToNextUser.
 *   2. waitlist already canceled/completed → hard no-op (no resurrection).
 *   3. private_call & below minimum balance → 'canceled' ("cancelled due to insufficient balance"); callToNextUser.
 *   4. consultant toggled the mode OFF → 'canceled' ("cancelled - consultant no longer available for this mode"); callToNextUser.
 *   5. user in a call, or consultant already progress/initiated → this waitlist deferred to 'missed'
 *      ("call deferred - <who> is busy").
 *   otherwise → openCall (redis hash + 'initiated' + socket emit + notify — a seam here).
 *
 * The state machine is wired only when the independent write/socket/call/timer/outbox gates are all
 * enabled. Re-entry for an already active waitlist is a hard no-op so a durable outbox retry cannot
 * reopen the same call or schedule duplicate billing timers.
 */
@Service
public class CallLifecycleService {

    public enum InitiateOutcome {
        CANCELED_PARTY_NOT_FOUND,
        NOOP_ALREADY_CLOSED,
        NOOP_ALREADY_ACTIVE,
        CANCELED_INSUFFICIENT_BALANCE,
        CANCELED_MODE_UNAVAILABLE,
        DEFERRED_MISSED,
        CANCELED_USER_UNREACHABLE,
        INITIATED
    }

    private final CallGuardStore store;

    public CallLifecycleService(CallGuardStore store) {
        this.store = store;
    }

    public InitiateOutcome initiateCall(String userId, String consultantId, String roomId, String callMode) {
        Document user = store.loadUser(userId);
        Document consultant = store.loadConsultant(consultantId);

        // (1) party missing
        if (user == null || consultant == null) {
            String who = (user == null) ? "user" : "consultant";
            store.cancelWaitlist(roomId, "cancelled - " + who + " not found");
            if (consultant != null) store.callToNextUser(consultantId, "user not found or deleted");
            return InitiateOutcome.CANCELED_PARTY_NOT_FOUND;
        }

        // (2) waitlist already closed → hard no-op (Node throws WAITLIST_ALREADY_CLOSED, caught → undefined)
        String status = store.loadWaitlistStatus(roomId);
        if ("canceled".equals(status) || "completed".equals(status)) {
            return InitiateOutcome.NOOP_ALREADY_CLOSED;
        }
        if ("initiated".equals(status) || "partially_accepted".equals(status)
                || "progress".equals(status)) {
            return InitiateOutcome.NOOP_ALREADY_ACTIVE;
        }

        // (3) minimum balance (private_call only, matching Node)
        if (!store.hasMinimumBalance(user, consultant, callMode, roomId)) {
            store.cancelWaitlist(roomId, "cancelled due to insufficient balance");
            store.callToNextUser(consultantId, "due to insufficient balance");
            return InitiateOutcome.CANCELED_INSUFFICIENT_BALANCE;
        }

        // (4) consultant toggled the mode off after the request
        if (!isModeLive(consultant, callMode)) {
            store.cancelWaitlist(roomId, "cancelled - consultant no longer available for this mode");
            store.callToNextUser(consultantId, "consultant no longer available");
            return InitiateOutcome.CANCELED_MODE_UNAVAILABLE;
        }

        // (5) either party is busy → defer this waitlist to 'missed'
        String busy = store.busyParty(userId, consultantId);
        if (busy != null) {
            store.setMissed(roomId, "call deferred - " + busy + " is busy");
            return InitiateOutcome.DEFERRED_MISSED;
        }

        String unreachableReason = store.validateUserReachability(
                user, consultant, userId, consultantId, roomId);
        if (unreachableReason != null) {
            return InitiateOutcome.CANCELED_USER_UNREACHABLE;
        }

        // passed all gates → open the consultant-scoped call owner
        store.openCall(roomId, userId, consultantId, callMode);
        return InitiateOutcome.INITIATED;
    }

    /** Node: session → always true; chat/audio/video keyed off sessionsStatus flags. */
    static boolean isModeLive(Document consultant, String callMode) {
        if ("session".equals(callMode)) return true;
        Document ss = consultant.get("sessionsStatus") instanceof Document
                ? (Document) consultant.get("sessionsStatus") : new Document();
        switch (callMode == null ? "" : callMode) {
            case "chat": return Boolean.TRUE.equals(ss.getBoolean("isChatLive"));
            case "audio": return Boolean.TRUE.equals(ss.getBoolean("isVoiceLive"));
            case "video": return Boolean.TRUE.equals(ss.getBoolean("isVideoLive"));
            default: return false;
        }
    }
}
