package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.crypto.CryptoUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AES wallet {@code coins} money-safety contract for {@link AesWalletService} (Node
 * userWalletUpdate / consultantWalletUpdate). Uses a REAL {@link CryptoUtil} so the read-decrypt /
 * write-encrypt invariant is exercised for real, with a fake in-memory cipher store.
 *
 * This directly pins the reviewer's #1 rule for Prompt D: the coins balance is NEVER stored plain —
 * it is always re-encrypted (crypto-js "Salted__") on write. Shadow-only / human-review gate.
 */
class AesWalletServiceTest {

    private static final String KEY = "vedicmeet_test_key_do_not_use_in_prod";
    private final CryptoUtil crypto = new CryptoUtil(KEY);

    @Test
    void debitUserCoins_subtracts_andReEncrypts() {
        FakeCoinsStore store = new FakeCoinsStore();
        store.userCipher.put("U", crypto.encrypt("100"));
        AesWalletService svc = new AesWalletService(crypto, store);

        double newBal = svc.debitUserCoins("U", 30);

        assertEquals(70.0, newBal);
        assertEquals(70.0, Double.parseDouble(crypto.decrypt(store.userCipher.get("U"))));
    }

    @Test
    void debitUserCoins_floorsAtZero() {
        FakeCoinsStore store = new FakeCoinsStore();
        store.userCipher.put("U", crypto.encrypt("10"));
        AesWalletService svc = new AesWalletService(crypto, store);

        double newBal = svc.debitUserCoins("U", 30); // 10 - 30 -> floored to 0

        assertEquals(0.0, newBal);
        assertEquals(0.0, Double.parseDouble(crypto.decrypt(store.userCipher.get("U"))));
    }

    @Test
    void creditConsultantCoins_addsToBalance() {
        FakeCoinsStore store = new FakeCoinsStore();
        store.consultantCipher.put("C", crypto.encrypt("50"));
        AesWalletService svc = new AesWalletService(crypto, store);

        double newBal = svc.creditConsultantCoins("C", 25);

        assertEquals(75.0, newBal);
        assertEquals(75.0, Double.parseDouble(crypto.decrypt(store.consultantCipher.get("C"))));
    }

    @Test
    void creditConsultantCoins_treatsMissingWalletAsZero() {
        FakeCoinsStore store = new FakeCoinsStore();
        AesWalletService svc = new AesWalletService(crypto, store);

        double newBal = svc.creditConsultantCoins("C", 40);

        assertEquals(40.0, newBal);
    }

    @Test
    void write_isNeverPlaintext_alwaysSaltedCiphertext() {
        FakeCoinsStore store = new FakeCoinsStore();
        store.userCipher.put("U", crypto.encrypt("100"));
        AesWalletService svc = new AesWalletService(crypto, store);

        svc.debitUserCoins("U", 1);

        String stored = store.userCipher.get("U");
        assertNotEquals("99", stored, "coins must never be stored plain");
        assertTrue(stored.startsWith("U2FsdGVkX1"), "must be crypto-js OpenSSL 'Salted__' base64");
    }

    @Test
    void jsNumber_matchesJsonStringify() {
        assertEquals("100", AesWalletService.jsNumber(100.0));
        assertEquals("18", AesWalletService.jsNumber(18.0));
        assertEquals("4.5", AesWalletService.jsNumber(4.5));
        assertEquals("0", AesWalletService.jsNumber(0.0));
    }

    static class FakeCoinsStore implements WalletCoinsStore {
        final Map<Object, String> userCipher = new LinkedHashMap<>();
        final Map<Object, String> consultantCipher = new LinkedHashMap<>();

        @Override public String findUserCoinsCipher(Object userId) { return userCipher.get(userId); }
        @Override public void setUserCoinsCipher(Object userId, String cipher) { userCipher.put(userId, cipher); }
        @Override public String findConsultantCoinsCipher(Object consultantId) { return consultantCipher.get(consultantId); }
        @Override public void setConsultantCoinsCipher(Object consultantId, String cipher) { consultantCipher.put(consultantId, cipher); }
    }
}
