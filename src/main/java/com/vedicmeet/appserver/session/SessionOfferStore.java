package com.vedicmeet.appserver.session;

import org.bson.Document;

/**
 * Read/consume seam for the consultation offers used by Node's session booking flow.
 * Selection is side-effect free; consumption happens inside the booking Mongo transaction.
 */
public interface SessionOfferStore {

    enum Kind { MANUAL_COUPON, SYSTEM_OFFER }

    record Selection(Kind kind, Object userId, Object stateId, Object offerRuleId,
                     Object variantId, Document coupon, Document rule, Document variant) {}

    Selection select(Document user, Document consultant, String requestedMode, String couponCode);

    void consume(Selection selection);
}
