package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Money-math + guard contract for {@link WalletService} (Node creditAndDebitOnWallet). Runs against
 * an in-memory {@link FakeWalletStore} so the balance arithmetic, low-balance guard (incl. the JS
 * coercion quirk), ledger transactionType, and consultant/gift commission are pinned deterministically.
 *
 * Part of the Prompt-D human-review gate: {@link WalletService} is shadow-only and unwired.
 */
class WalletServiceTest {

    @Test
    void userCredit_addsToWallet_writesCreditLedger_firesSessionExtend() {
        FakeWalletStore store = new FakeWalletStore();
        store.userWallets.put("U", 100.0);
        WalletService svc = new WalletService(store);

        svc.creditAndDebitOnWallet("user", "credit",
                new Document("userId", "U").append("coins", 50));

        assertEquals(150.0, store.userWallets.get("U"));
        assertEquals(1, store.ledgers.size());
        Document led = store.ledgers.get(0);
        assertEquals(0, ((Number) led.get("transactionType")).intValue());
        assertEquals(50.0, ((Number) led.get("coins")).doubleValue());
        assertEquals(1, store.sessionExtends.size(), "user credit extends any ongoing session");
    }

    @Test
    void userDebit_subtracts_writesDebitLedger_withPlatformCommission() {
        FakeWalletStore store = new FakeWalletStore();
        store.userWallets.put("U", 100.0);
        WalletService svc = new WalletService(store);

        svc.creditAndDebitOnWallet("user", "debit",
                new Document("userId", "U").append("coins", 30)
                        .append("transactionFor", "consult").append("adminCommision", 40));

        assertEquals(70.0, store.userWallets.get("U"));
        Document led = store.ledgers.get(0);
        assertEquals(1, ((Number) led.get("transactionType")).intValue());
        assertEquals(30.0, ((Number) led.get("coins")).doubleValue());
        // getAdminCommissionAmount('consult',30,40) = 30 - 30*40/100 = 18
        assertEquals(18.0, ((Number) led.get("totalAmountPayToPlateform")).doubleValue());
    }

    @Test
    void debit_consult_insufficientBalance_throws_noMutation() {
        FakeWalletStore store = new FakeWalletStore();
        store.userWallets.put("U", 5.0); // remaining*5 = 25 < 30
        WalletService svc = new WalletService(store);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                svc.creditAndDebitOnWallet("user", "debit",
                        new Document("userId", "U").append("coins", 30).append("transactionFor", "consult")));
        assertTrue(ex.getMessage().contains("Recharge your wallet"));
        assertEquals(5.0, store.userWallets.get("U"), "no debit on guard failure");
        assertEquals(0, store.ledgers.size());
    }

    @Test
    void debit_consult_zeroBalance_usesCoercionGuard_throws() {
        FakeWalletStore store = new FakeWalletStore();
        store.userWallets.put("U", 0.0); // remaining==0 -> Node coerces left side to 1; 1 < 2 -> throw
        WalletService svc = new WalletService(store);

        assertThrows(RuntimeException.class, () ->
                svc.creditAndDebitOnWallet("user", "debit",
                        new Document("userId", "U").append("coins", 2).append("transactionFor", "consult")));
    }

    @Test
    void consultantCredit_appliesAdminCommission_ledgerUsesConsultantCharge() {
        FakeWalletStore store = new FakeWalletStore();
        store.consultantWallets.put("C", 0.0);
        WalletService svc = new WalletService(store);

        svc.creditAndDebitOnWallet("cons", "credit",
                new Document("consultantId", "C").append("coins", 100).append("adminCommision", 40));

        assertEquals(40.0, store.consultantWallets.get("C"), "100 * 40% = 40 credited");
        Document led = store.ledgers.get(0);
        assertEquals(0, ((Number) led.get("transactionType")).intValue());
        assertEquals(40.0, ((Number) led.get("coins")).doubleValue());
    }

    @Test
    void giftCredit_toConsultant_usesPlatformShare_forBalanceAndLedger() {
        FakeWalletStore store = new FakeWalletStore();
        store.consultantWallets.put("C", 10.0);
        store.consultantPrice.put("C", 30.0); // price.platformShare = 30
        WalletService svc = new WalletService(store);

        svc.creditAndDebitOnWallet("cons", "credit",
                new Document("consultantId", "C").append("coins", 100).append("transactionFor", "gift"));

        // newCoins = 100*30/100 + 10 = 40 ; coins reassigned to 30 ; ledger (cons+gift) coins = 30
        assertEquals(40.0, store.consultantWallets.get("C"));
        assertEquals(30.0, ((Number) store.ledgers.get(0).get("coins")).doubleValue());
    }

    // ---- in-memory store ----

    static class FakeWalletStore implements WalletStore {
        final Map<Object, Double> userWallets = new LinkedHashMap<>();
        final Map<Object, Double> consultantWallets = new LinkedHashMap<>();
        final Map<Object, Double> consultantPrice = new LinkedHashMap<>(); // platformShare
        final List<Document> ledgers = new CopyOnWriteArrayList<>();
        final List<Object[]> sessionExtends = new CopyOnWriteArrayList<>();

        @Override public Document findUserWallet(Object userId) {
            return userWallets.containsKey(userId) ? new Document("wallet", userWallets.get(userId)) : null;
        }

        @Override public Document findConsultantWallet(Object consultantId) {
            if (!consultantWallets.containsKey(consultantId)) return null;
            Document d = new Document("wallet", consultantWallets.get(consultantId));
            if (consultantPrice.containsKey(consultantId)) {
                d.append("price", new Document("platformShare", consultantPrice.get(consultantId)));
            }
            return d;
        }

        @Override public void setUserWallet(Object userId, double wallet) { userWallets.put(userId, wallet); }
        @Override public void setConsultantWallet(Object consultantId, double wallet) { consultantWallets.put(consultantId, wallet); }
        @Override public void extendOngoingSessionTime(Object userId, double coins) { sessionExtends.add(new Object[]{userId, coins}); }
        @Override public void insertLedger(Document payloadForWallet) { ledgers.add(payloadForWallet); }
    }
}
