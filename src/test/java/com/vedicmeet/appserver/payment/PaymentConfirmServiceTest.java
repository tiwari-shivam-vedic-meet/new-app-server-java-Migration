package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful idempotency of {@link PaymentConfirmService} (Node confirmPayment L519-606). */
class PaymentConfirmServiceTest {

    @Test
    void absentOrder_throws() {
        Fake s = new Fake();
        s.tx = null;
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PaymentConfirmService(s).confirmPayment("ORD", "PAY", false));
        assertTrue(ex.getMessage().contains("Order is not present"));
    }

    @Test
    void alreadyCompleted_returnsSuccess_withoutCrediting() {
        Fake s = new Fake();
        s.tx = new Document("status", "COMPLETED");
        var r = new PaymentConfirmService(s).confirmPayment("ORD", "PAY", false);
        assertTrue(r.success);
        assertFalse(s.atomicCalled, "no atomic transition for an already-completed order");
        assertFalse(s.credited, "no re-credit");
    }

    @Test
    void alreadyPaid_returnsSuccess_withoutCrediting() {
        Fake s = new Fake();
        s.tx = new Document("status", "paid");
        assertTrue(new PaymentConfirmService(s).confirmPayment("ORD", "PAY", false).success);
        assertFalse(s.credited);
    }

    @Test
    void lostAtomicRace_returnsSuccess_withoutCrediting() {
        Fake s = new Fake();
        s.tx = new Document("status", "INITIATED").append("userId", "U1");
        s.updated = null; // another request already won
        var r = new PaymentConfirmService(s).confirmPayment("ORD", "PAY", false);
        assertTrue(r.success);
        assertTrue(s.atomicCalled);
        assertFalse(s.credited, "loser must NOT credit");
    }

    @Test
    void winner_creditsOnce_andRunsCouponRewards() {
        Fake s = new Fake();
        s.tx = new Document("status", "INITIATED").append("userId", "U1");
        s.updated = new Document("_id", "T1").append("userId", "U1").append("coins", 100);
        var r = new PaymentConfirmService(s).confirmPayment("ORD", "PAY9", false);
        assertTrue(r.success);
        assertTrue(s.credited, "winner credits the wallet");
        assertEquals("PAY9", s.creditPaymentId);
        assertTrue(s.couponRewardsRun);
    }

    @Test
    void firstEverPayment_setsMetaEventFiredTrueOnAtomicUpdate() {
        Fake s = new Fake();
        s.tx = new Document("status", "INITIATED").append("userId", "U1");
        s.previousPaid = 0; // no prior paid tx
        s.updated = new Document("_id", "T1").append("userId", "U1").append("coins", 10);
        new PaymentConfirmService(s).confirmPayment("ORD", "PAY", false);
        assertTrue(s.atomicMetaEventFired, "first-ever payment marks metaEventFired");
    }

    @Test
    void repeatCustomer_leavesMetaEventFiredFalse() {
        Fake s = new Fake();
        s.tx = new Document("status", "INITIATED").append("userId", "U1");
        s.previousPaid = 3;
        s.updated = new Document("_id", "T1").append("userId", "U1").append("coins", 10);
        new PaymentConfirmService(s).confirmPayment("ORD", "PAY", false);
        assertFalse(s.atomicMetaEventFired);
    }

    static class Fake implements PaymentConfirmStore {
        Document tx, updated;
        long previousPaid = 0;
        boolean atomicCalled, credited, couponRewardsRun, atomicMetaEventFired;
        String creditPaymentId;

        @Override public Document findTransactionByOrderId(String orderId) { return tx; }
        @Override public long countPreviousPaid(Object userId) { return previousPaid; }
        @Override public Document atomicComplete(String orderId, String paymentId, boolean metaEventFired, boolean eventQueuedFired) {
            atomicCalled = true;
            atomicMetaEventFired = metaEventFired;
            return updated;
        }
        @Override public void creditUserForConfirm(Document updatedTx, String paymentId) { credited = true; creditPaymentId = paymentId; }
        @Override public void handleRechargeCouponRewards(Document updatedTx) { couponRewardsRun = true; }
    }
}
