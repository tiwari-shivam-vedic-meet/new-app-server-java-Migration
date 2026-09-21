package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.crypto.CryptoUtil;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * purchaseMembership contract for MembershipPurchaseService (Node transaction.js L736), ported
 * FAITHFULLY (team decision: keep Node logic exactly). Coins balance is read via the MembershipStore
 * (waitlistModel.findOne({userId}).coins) and decrypted with a real AesWalletService; the SILVER
 * cons-credit/user-debit go through a real WalletService. Shadow-only / money gate.
 */
class MembershipPurchaseServiceTest {

    private static final String KEY = "vedicmeet_test_key_do_not_use_in_prod";
    private final CryptoUtil crypto = new CryptoUtil(KEY);

    private MembershipPurchaseService build(FakeMembershipStore ms, WalletServiceTest.FakeWalletStore ws) {
        AesWalletService aes = new AesWalletService(crypto, new AesWalletServiceTest.FakeCoinsStore());
        return new MembershipPurchaseService(ms, aes, new WalletService(ws));
    }

    private Document membership(String plan, double discountPrice, double price, int duration) {
        return new Document("_id", "m1").append("status", true).append("planType", plan)
                .append("membershipDiscountPrice", discountPrice).append("membershipPrice", price)
                .append("membershipDuration", duration).append("discountPercentage", 10);
    }

    @Test
    void membershipNotExist_throws() {
        FakeMembershipStore ms = new FakeMembershipStore();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                build(ms, new WalletServiceTest.FakeWalletStore())
                        .purchaseMembership(new Document("memberhsipId", "nope").append("coins", 100),
                                new Document("_id", "U")));
        assertTrue(ex.getMessage().contains("Membership is not exist"));
    }

    @Test
    void priceMismatch_throws() {
        FakeMembershipStore ms = new FakeMembershipStore();
        ms.membership = membership("GOLD", 100, 150, 1);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                build(ms, new WalletServiceTest.FakeWalletStore())
                        .purchaseMembership(new Document("memberhsipId", "m1").append("coins", 99),
                                new Document("_id", "U")));
        assertTrue(ex.getMessage().contains("Plan amount is not matched"));
    }

    @Test
    void silverWithoutConsultant_throws() {
        FakeMembershipStore ms = new FakeMembershipStore();
        ms.membership = membership("SILVER", 100, 150, 1);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                build(ms, new WalletServiceTest.FakeWalletStore())
                        .purchaseMembership(new Document("memberhsipId", "m1").append("coins", 100),
                                new Document("_id", "U")));
        assertTrue(ex.getMessage().contains("choose consultant first"));
    }

    @Test
    void insufficientCoins_throws() {
        FakeMembershipStore ms = new FakeMembershipStore();
        ms.membership = membership("GOLD", 100, 150, 1);
        ms.coinsCipher = crypto.encrypt("50");
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                build(ms, new WalletServiceTest.FakeWalletStore())
                        .purchaseMembership(new Document("memberhsipId", "m1").append("coins", 100),
                                new Document("_id", "U")));
        assertTrue(ex.getMessage().contains("Recharge your wallet"));
    }

    @Test
    void nullCoins_readsZero_throws_matchesNodeWaitlistQuirk() {
        FakeMembershipStore ms = new FakeMembershipStore();
        ms.membership = membership("GOLD", 100, 150, 1);
        ms.coinsCipher = null;
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                build(ms, new WalletServiceTest.FakeWalletStore())
                        .purchaseMembership(new Document("memberhsipId", "m1").append("coins", 100),
                                new Document("_id", "U")));
        assertTrue(ex.getMessage().contains("Recharge your wallet"));
    }

    @Test
    void silverHappyPath_creditsConsultant_debitsUser_updatesSavedAmount() {
        FakeMembershipStore ms = new FakeMembershipStore();
        ms.membership = membership("SILVER", 100, 150, 1);
        ms.coinsCipher = crypto.encrypt("500");
        WalletServiceTest.FakeWalletStore ws = new WalletServiceTest.FakeWalletStore();
        ws.userWallets.put("U", 1000.0);
        ws.consultantWallets.put("C", 0.0);

        Document input = new Document("memberhsipId", "m1").append("coins", 100).append("consultantId", "C");
        build(ms, ws).purchaseMembership(input, new Document("_id", "U").append("isMembership", false));

        assertEquals(900.0, ws.userWallets.get("U"), "user plain wallet debited 100");
        assertEquals(50.0, ws.consultantWallets.get("C"), "consultant credited 100 * 50% default commission");
        assertEquals(50.0, ms.savedAmount, "savedAmount += (membershipPrice 150 - discountPrice 100)");
        assertEquals("SILVER", ms.notifiedPlan);
    }

    static class FakeMembershipStore implements MembershipStore {
        Document membership;
        String coinsCipher;
        double savedAmount = 0;
        String notifiedPlan;

        @Override public Document findActiveMembership(String membershipId) {
            return (membership != null && membershipId != null && membershipId.equals(membership.get("_id"))) ? membership : null;
        }
        @Override public String findWalletCoinsCipher(Object userId) { return coinsCipher; }
        @Override public double getSavedAmount(Object userId) { return savedAmount; }
        @Override public void setSavedAmount(Object userId, double s) { savedAmount = s; }
        @Override public void sendMembershipNotification(Document user, String membershipPlanType) { notifiedPlan = membershipPlanType; }
    }
}
