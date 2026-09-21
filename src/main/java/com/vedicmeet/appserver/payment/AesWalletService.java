package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.crypto.CryptoUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * ⚠ SHADOW-ONLY — HUMAN REVIEW REQUIRED. Faithful port of the AES-encrypted wallet {@code coins}
 * settlement writes: Node {@code userWalletUpdate} / {@code consultantWalletUpdate}
 * (utils/classes/db-query.js L870/L915; same pattern in support.js/user.js/consultant.js).
 *
 * The {@code coins} field is stored ENCRYPTED at rest (crypto-js "Salted__"). This service upholds
 * the invariant the reviewer flagged: READ with {@link CryptoUtil#decrypt}, WRITE with
 * {@link CryptoUtil#encrypt} — never store plain. Because {@code CryptoUtil} is byte-compatible with
 * crypto-js (verified in CryptoUtilTest + here), values written by Java decrypt in Node and vice versa.
 *
 * Node parity details preserved:
 *   - the number is JSON.stringify'd before encryption (so 100.0 → "100", 4.5 → "4.5").
 *   - user debit floors at 0 ({@code if (userUpdateCoinsNow < 0) userUpdateCoinsNow = 0}).
 *   - consultant credit treats a missing/blank balance as 0.
 *
 * NOT wired to any route. The ledger row + notifications that Node fires alongside these are
 * settlement/call-lifecycle concerns (Prompt E) and are intentionally out of this slice.
 */
@Service
public class AesWalletService {

    private final CryptoUtil crypto;
    private final WalletCoinsStore store;

    public AesWalletService(CryptoUtil crypto, WalletCoinsStore store) {
        this.crypto = crypto;
        this.store = store;
    }

    /** userWalletUpdate: coins := max(0, decrypt(coins) - deduct), re-encrypted. Returns new balance. */
    public double debitUserCoins(Object userId, double deduct) {
        double remaining = readCoins(store.findUserCoinsCipher(userId));
        double updated = remaining - deduct;
        if (updated < 0) updated = 0;
        store.setUserCoinsCipher(userId, crypto.encrypt(jsNumber(updated)));
        return updated;
    }

    /** consultantWalletUpdate: coins := decrypt(coins||0) + pay, re-encrypted. Returns new balance. */
    public double creditConsultantCoins(Object consultantId, double pay) {
        double remaining = readCoins(store.findConsultantCoinsCipher(consultantId));
        double updated = remaining + pay;
        store.setConsultantCoinsCipher(consultantId, crypto.encrypt(jsNumber(updated)));
        return updated;
    }

    /** Node: dataDecryption(coins, true) then Number(...); a null/blank cipher is treated as 0. */
    public double readCoins(String cipher) {
        if (cipher == null || cipher.isEmpty()) return 0;
        String plain = crypto.decrypt(cipher);
        if (plain == null || plain.isEmpty()) return 0;
        try {
            return Double.parseDouble(plain.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Match JSON.stringify(number): integral → no decimal point; otherwise shortest plain decimal. */
    static String jsNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
