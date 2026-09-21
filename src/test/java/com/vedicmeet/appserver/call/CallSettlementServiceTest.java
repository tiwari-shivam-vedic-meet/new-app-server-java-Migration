package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.crypto.CryptoUtil;
import com.vedicmeet.appserver.payment.AesWalletService;
import com.vedicmeet.appserver.payment.WalletCoinsStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Call-end settlement contract for {@link CallSettlementService} (Node db-query.js L520-573).
 * The amount split is pure; the wallet moves go through a REAL {@link AesWalletService} + {@link CryptoUtil}
 * over an in-memory coins store, so the end-to-end AES debit/credit is exercised. Shadow-only / gate.
 */
class CallSettlementServiceTest {

    private static final String KEY = "vedicmeet_test_key_do_not_use_in_prod";

    @Test
    void compute_billsBeyondFreeTrial_andSplitsCommission() {
        CallSettlementService svc = new CallSettlementService(new AesWalletService(new CryptoUtil(KEY), new FakeCoins()));
        // 10 min, 10/min, commission 60%, free trial 5 → billable 5 → deduct 50; platform 30; consultant 20
        CallSettlementService.Settlement s = svc.compute(10, 10, 60, 5);
        assertEquals(50.0, s.deduct);
        assertEquals(30.0, s.platform);
        assertEquals(20.0, s.consultantPay);
        assertEquals(s.deduct, s.platform + s.consultantPay, 1e-9, "platform + consultant == deduct");
    }

    @Test
    void compute_underFreeTrial_isFree() {
        CallSettlementService svc = new CallSettlementService(new AesWalletService(new CryptoUtil(KEY), new FakeCoins()));
        CallSettlementService.Settlement s = svc.compute(3, 10, 60, 5);
        assertEquals(0.0, s.deduct);
        assertEquals(0.0, s.consultantPay);
    }

    @Test
    void settle_movesAesCoins_userDebited_consultantCredited() {
        CryptoUtil crypto = new CryptoUtil(KEY);
        FakeCoins coins = new FakeCoins();
        coins.userCipher.put("U", crypto.encrypt("1000"));
        coins.consultantCipher.put("C", crypto.encrypt("0"));
        CallSettlementService svc = new CallSettlementService(new AesWalletService(crypto, coins));

        CallSettlementService.Settlement s = svc.settle("U", "C", 10, 10, 60, 5);

        assertEquals(50.0, s.deduct);
        assertEquals(950.0, Double.parseDouble(crypto.decrypt(coins.userCipher.get("U"))), "user 1000 - 50");
        assertEquals(20.0, Double.parseDouble(crypto.decrypt(coins.consultantCipher.get("C"))), "consultant 0 + 20");
        // coins are still encrypted at rest
        assertTrue(coins.userCipher.get("U").startsWith("U2FsdGVkX1"));
    }

    static class FakeCoins implements WalletCoinsStore {
        final Map<Object, String> userCipher = new LinkedHashMap<>();
        final Map<Object, String> consultantCipher = new LinkedHashMap<>();
        @Override public String findUserCoinsCipher(Object userId) { return userCipher.get(userId); }
        @Override public void setUserCoinsCipher(Object userId, String cipher) { userCipher.put(userId, cipher); }
        @Override public String findConsultantCoinsCipher(Object consultantId) { return consultantCipher.get(consultantId); }
        @Override public void setConsultantCoinsCipher(Object consultantId, String cipher) { consultantCipher.put(consultantId, cipher); }
    }
}
