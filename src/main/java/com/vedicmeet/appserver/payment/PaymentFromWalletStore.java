package com.vedicmeet.appserver.payment;

/**
 * Extra reads/writes that Node {@code paymentFromWallet} needs beyond the wallet credit/debit
 * (utils/classes/transaction.js L678). Behind a port so the orchestration is unit-testable.
 */
public interface PaymentFromWalletStore {

    /** waitlistModel.findOne({ user_id: userId.toString(), status:'progress' }) != null. */
    boolean isUserOnCall(Object userId);

    /** masterModel.findOne({},{payment:1}).payment.commission — defaults to 40 when absent. */
    double getAdminCommission();

    /** commentsModel.create({ broadcastId, userId, type:'1', giftId, comment }) on a gift-with-broadcast. */
    void insertGiftComment(Object broadcastId, Object userId, Object giftId, String comment);

    /**
     * Node reads {@code user.lastBroadcastJoin} and, if set, calls {@code updateWaitlistOfUser}
     * (a call-lifecycle concern). Seam here — the real update belongs to Prompt E.
     */
    void maybeUpdateWaitlist(Object userId);
}
