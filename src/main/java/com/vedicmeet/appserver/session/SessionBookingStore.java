package com.vedicmeet.appserver.session;

import org.bson.Document;

import java.time.Duration;

/** Persistence boundary for the high-concurrency mobile session-booking entry flow. */
public interface SessionBookingStore {

    record Lease(String key, String token) {}

    record ScheduledCharge(double amount, int minutes, int discountPercentage,
                           String slotDay, Document slotTime) {}

    record PersistResult(Document waitlist, long queueLengthBeforeJoin,
                         boolean callInitiationQueued) {}

    Document loadUser(Object userId);

    Document loadConsultant(String consultantId);

    boolean isUserBlocked(Object userId, Object consultantId);

    boolean hasActiveWaitlist(String userId);

    void updateFcmTokens(Object userId, java.util.List<String> tokens);

    String nextOrderId();

    Lease acquire(String key, Duration ttl);

    void release(Lease lease);

    /**
     * Re-checks all mutable guards, consumes any offer, and commits waitlist/slot/wallet/ledger/
     * outbox records as one transaction.
     */
    PersistResult persist(Document waitlist, SessionBookingPricingService.Quote quote,
                          ScheduledCharge scheduledCharge, boolean modeLive,
                          String userName, String consultantName);

    void enqueueChatFormRetry(String waitlistId, String threadId, Document form);
}
