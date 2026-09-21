package com.vedicmeet.appserver.call;

import org.bson.Document;

/**
 * Port for the DB / redis / socket / notify effects the call-initiate state machine needs
 * (Node utils/classes/call.js initiateCall). Behind an interface so the STATE TRANSITIONS are
 * unit-testable deterministically (the Jest call-lifecycle.test.js gates), without dragging in the
 * coupon-based pricing engine, Redis, or Socket.IO.
 *
 * The balance decision ({@link #hasMinimumBalance}) is a seam: Node computes it several ways by
 * coupon type (calculateSessionPriceAndDuration + first/second-purchase master settings). The
 * control flow — which transition fires — is what this port makes testable; the pricing itself is a
 * separate concern to port under its own review.
 */
public interface CallGuardStore {

    /** user.findOne({_id, isDeleted:false}) — null if missing/deleted. */
    Document loadUser(String userId);

    /** consultant.findById(consultantId) — null if missing. */
    Document loadConsultant(String consultantId);

    /** waitlist.findById(roomId).status — the current lifecycle status, or null. */
    String loadWaitlistStatus(String roomId);

    /** True when the user clears the minimum-balance gate for this mode/coupon (seam over pricing). */
    boolean hasMinimumBalance(Document user, Document consultant, String callMode, String roomId);

    /**
     * The busy party for this pair, or null when neither is busy. Node:
     * waitlist.findOne({ $or:[{user_id,status:'progress'},{consultant_id,status:{$in:['progress','initiated']}}] })
     * then whoIsBusy = row.user_id === userId ? 'user' : 'consultant'.
     */
    String busyParty(String userId, String consultantId);

    /**
     * Node sends a data-only FCM probe before opening a call. Return a failure reason after applying
     * Node's token-clear/cancel/queue-next effects, or {@code null} when the user is reachable.
     */
    default String validateUserReachability(Document user, Document consultant,
                                            String userId, String consultantId, String roomId) {
        return null;
    }

    /** waitlist.updateOne({_id:roomId}, {$set:{status:'canceled'}, $push:{logs:{callStatus:log}}}). */
    void cancelWaitlist(String roomId, String logMessage);

    /** waitlist.updateOne({_id:roomId,status:'waiting'}, {$set:{status:'missed'}, $push:{logs:{callStatus:log}}}). */
    void setMissed(String roomId, String logMessage);

    /** CallManager.callToNextUser(consultantId, reason) — advances the consultant's queue. */
    void callToNextUser(String consultantId, String reason);

    /** Happy path: set the redis call hash + waitlist 'initiated' + emit socket + notify. Seam. */
    void openCall(String roomId, String userId, String consultantId, String callMode);
}
