package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.Map;

/** External effects needed by the current production {@code handleCallTimeout} flow. */
public interface CallCompletionStore {

    record Amounts(double baseAmount, double platformAmount, double consultantAmount,
                   double userAmount, double extraDuration, double extraDurationAmount,
                   String sessionType, double callDurationSeconds) {}

    record ApplyResult(boolean claimed, double actualUserDeduction, Document completedWaitlist) {}

    record CompletionPolicy(boolean blockConsultantPayout,
                            boolean showFirstConsultationAgain,
                            String replacementDeviceToken) {}

    Map<String, String> getCallHash(String roomId);

    Document findWaitlist(String roomId);

    Document findUser(String userId);

    Document findConsultant(String consultantId);

    Document findOfferVariant(Object variantId);

    Document findV2Coupon(Object couponId);

    /** Node first-purchase conversion and first-consultation replay rules. */
    default CompletionPolicy evaluateCompletionPolicy(String consultantId, String callEndedBy,
                                                       Document waitlist) {
        return new CompletionPolicy(false, false, null);
    }

    /** Atomic progress->completed claim plus wallet and ledger writes in one Mongo transaction. */
    ApplyResult claimAndApply(String roomId, String userId, String consultantId,
                              String callStatus, String callEndedBy, Amounts amounts,
                              Document waitlist, Document user, Document consultant,
                              CompletionPolicy policy);

    /** Redis clocks/hash + consultant ownership lock cleanup after the Mongo commit. */
    void clearRuntime(String roomId, String consultantId);

    void emitLeaveRooms(String userId, String consultantId, Document waitlist,
                        String callEndedBy, boolean showFirstConsultationAgain);

    void notifyCallEnded(String userId, String consultantId, Document user, Document consultant,
                         Document waitlist, Amounts amounts, double actualUserDeduction);

    void deleteCallInitiated(String roomId, String userId, String consultantId);

    void enqueuePostCallWork(String userId, String consultantId, Document waitlist,
                             Amounts amounts, double actualUserDeduction, String callEndedBy);

    void callNextWaitingUser(Document consultant, String completedRoomId, String callEndedBy);
}
