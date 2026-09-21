package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end orchestration contract for {@link PaymentFromWalletService} (Node paymentFromWallet).
 * Uses a real {@link WalletService} over an in-memory {@code WalletServiceTest.FakeWalletStore} plus a
 * fake {@link PaymentFromWalletStore}, so the wiring (debit user → credit consultant → gift comment →
 * on-call guard) is verified against actual balance changes. Shadow-only / human-review gate.
 */
class PaymentFromWalletServiceTest {

    private PaymentFromWalletService build(WalletServiceTest.FakeWalletStore ws, FakePfwStore pfw) {
        return new PaymentFromWalletService(new WalletService(ws), pfw);
    }

    @Test
    void onCallInProgress_throws_noWalletMovement() {
        WalletServiceTest.FakeWalletStore ws = new WalletServiceTest.FakeWalletStore();
        ws.userWallets.put("U", 100.0);
        FakePfwStore pfw = new FakePfwStore();
        pfw.onCall = true;

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                build(ws, pfw).paymentFromWallet(
                        new Document("coins", 10).append("transactionFor", "consult").append("consultantId", "C"),
                        new Document("_id", "U").append("name", "Buyer")));
        assertTrue(ex.getMessage().contains("on call in progress"));
        assertEquals(100.0, ws.userWallets.get("U"));
        assertEquals(0, ws.ledgers.size());
    }

    @Test
    void consult_debitsUser_and_creditsConsultant() {
        WalletServiceTest.FakeWalletStore ws = new WalletServiceTest.FakeWalletStore();
        ws.userWallets.put("U", 100.0);
        ws.consultantWallets.put("C", 0.0);
        FakePfwStore pfw = new FakePfwStore();
        pfw.commission = 40;

        build(ws, pfw).paymentFromWallet(
                new Document("coins", 10).append("transactionFor", "consult").append("consultantId", "C"),
                new Document("_id", "U").append("name", "Buyer"));

        assertEquals(90.0, ws.userWallets.get("U"), "user debited 10");
        assertEquals(4.0, ws.consultantWallets.get("C"), "consultant credited 10 * 40% = 4");
        assertEquals(2, ws.ledgers.size(), "one debit + one credit ledger");
    }

    @Test
    void topup_debitsUserOnly_noConsultantCredit() {
        WalletServiceTest.FakeWalletStore ws = new WalletServiceTest.FakeWalletStore();
        ws.userWallets.put("U", 100.0);
        FakePfwStore pfw = new FakePfwStore();

        build(ws, pfw).paymentFromWallet(
                new Document("coins", 20), // no transactionFor -> topup, no low-balance guard
                new Document("_id", "U").append("name", "Buyer"));

        assertEquals(80.0, ws.userWallets.get("U"));
        assertEquals(1, ws.ledgers.size());
    }

    @Test
    void giftWithBroadcast_insertsGiftComment() {
        WalletServiceTest.FakeWalletStore ws = new WalletServiceTest.FakeWalletStore();
        ws.userWallets.put("U", 100.0);
        ws.consultantWallets.put("C", 0.0);
        ws.consultantPrice.put("C", 30.0);
        FakePfwStore pfw = new FakePfwStore();

        build(ws, pfw).paymentFromWallet(
                new Document("coins", 10).append("transactionFor", "gift").append("consultantId", "C")
                        .append("broadcastId", "B").append("giftId", "G").append("walletDeductReason", "Rose"),
                new Document("_id", "U").append("name", "Buyer"));

        assertEquals(1, pfw.comments.size());
        assertEquals("Buyer gifted Rose", pfw.comments.get(0).getString("comment"));
    }

    static class FakePfwStore implements PaymentFromWalletStore {
        boolean onCall = false;
        double commission = 40;
        final List<Document> comments = new CopyOnWriteArrayList<>();
        int waitlistUpdates = 0;

        @Override public boolean isUserOnCall(Object userId) { return onCall; }
        @Override public double getAdminCommission() { return commission; }
        @Override public void insertGiftComment(Object broadcastId, Object userId, Object giftId, String comment) {
            comments.add(new Document("broadcastId", broadcastId).append("userId", userId)
                    .append("giftId", giftId).append("comment", comment));
        }
        @Override public void maybeUpdateWaitlist(Object userId) { waitlistUpdates++; }
    }
}
