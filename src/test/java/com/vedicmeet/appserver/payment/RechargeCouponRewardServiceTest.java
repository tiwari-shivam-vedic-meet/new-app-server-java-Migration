package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RechargeCouponRewardServiceTest {

    @Test
    void noCoupon_isNoOp() {
        Fake store = new Fake();
        boolean applied = new RechargeCouponRewardService(store).process(
                new Document("_id", new ObjectId()).append("userId", new ObjectId()));
        assertFalse(applied);
        assertNull(store.recorded);
    }

    @Test
    void duplicateTransaction_isNoOp() {
        Fake store = validStore();
        store.duplicate = true;
        assertFalse(new RechargeCouponRewardService(store).process(transaction()));
        assertNull(store.recorded);
    }

    @Test
    void invalidCouponType_isNoOp_likeNodeBestEffortPath() {
        Fake store = validStore();
        store.coupon.put("type", "GENERAL");
        assertFalse(new RechargeCouponRewardService(store).process(transaction()));
        assertNull(store.recorded);
    }

    @Test
    void validInfluencerCoupon_recordsExactNodeCalculations() {
        Fake store = validStore();
        assertTrue(new RechargeCouponRewardService(store).process(transaction()));
        assertNotNull(store.recorded);
        assertEquals("SAVE10", store.recorded.couponCode());
        assertEquals(100.0, store.recorded.extraCoins());
        assertEquals(20.0, store.recorded.influencerEarning());
        assertEquals(1180.0, store.recorded.finalAmountPaid());
        assertEquals(0.0, store.recorded.discountAmount());
    }

    @Test
    void writeFailure_propagates_soPaymentTransactionCanRollback() {
        Fake store = validStore();
        store.writeFailure = new IllegalStateException("ledger unavailable");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RechargeCouponRewardService(store).process(transaction()));
        assertEquals("ledger unavailable", failure.getMessage());
    }

    private static Document transaction() {
        return new Document("_id", new ObjectId())
                .append("userId", new ObjectId())
                .append("couponCode", " save10 ")
                .append("baseAmount", 1000)
                .append("gstAmount", 180)
                .append("paidAmount", 1180);
    }

    private static Fake validStore() {
        Fake store = new Fake();
        ObjectId influencerId = new ObjectId();
        store.coupon = new Document("_id", new ObjectId())
                .append("code", "SAVE10")
                .append("isActive", true)
                .append("type", "INFLUENCER")
                .append("appliesOn", "RECHARGE")
                .append("discountPercent", 10)
                .append("usageLimit", 5)
                .append("usageCount", 1)
                .append("influencerId", influencerId);
        store.influencer = new Document("_id", influencerId)
                .append("isActive", true).append("sharePercent", 2);
        return store;
    }

    static class Fake implements RechargeCouponRewardStore {
        Document saved, coupon, influencer;
        boolean duplicate;
        RuntimeException writeFailure;
        Reward recorded;

        @Override public Document findLatestSavedPayment(Object userId) { return saved; }
        @Override public Document findActiveCoupon(String code) { return coupon; }
        @Override public Document findActiveInfluencer(Object influencerId) { return influencer; }
        @Override public boolean rewardAlreadyRecorded(Object transactionId) { return duplicate; }
        @Override public void record(Reward reward) {
            if (writeFailure != null) throw writeFailure;
            recorded = reward;
        }
    }
}
