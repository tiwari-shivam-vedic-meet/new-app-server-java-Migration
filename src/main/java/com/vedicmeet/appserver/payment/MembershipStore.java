package com.vedicmeet.appserver.payment;

import org.bson.Document;

/** Membership lookup + the user's coins/savedAmount, reproducing Node's EXACT queries. */
public interface MembershipStore {

    /** membershipDiscountModel.findOne({ _id, status:true }) — null if absent/inactive. */
    Document findActiveMembership(String membershipId);

    /**
     * Node: {@code waitlistModel.findOne({ userId }).coins} (utils/classes/transaction.js L765).
     * FAITHFUL: this queries the SAME collection/field Node does — including the quirk that the
     * waitlist collection keys on {@code user_id} (not {@code userId}), so this typically returns null.
     * Returns the raw (encrypted) coins string, or null. Preserved intentionally per the team.
     */
    String findWalletCoinsCipher(Object userId);

    /** Node: {@code waitlistModel.findOne({ userId }).savedAmount} (L800), or 0. */
    double getSavedAmount(Object userId);

    /** Node: {@code waitlistModel.findOneAndUpdate({ userId }, { $set:{ savedAmount } })} (L803). */
    void setSavedAmount(Object userId, double savedAmount);

    /** sendNotificationOnMembership seam (push). */
    void sendMembershipNotification(Document user, String membershipPlanType);
}
