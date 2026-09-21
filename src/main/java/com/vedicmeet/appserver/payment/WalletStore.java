package com.vedicmeet.appserver.payment;

import org.bson.Document;

/**
 * Port for the wallet balance + ledger operations of Node {@code creditAndDebitOnWallet}
 * (utils/classes/transaction.js L160). Kept behind an interface so {@link WalletService}'s money
 * math is unit-testable without a DB (see WalletServiceTest).
 *
 * IMPORTANT (for the reviewer): this path reads/writes the PLAIN numeric {@code wallet} field on the
 * user/consultant document — exactly what Node does here. The AES-encrypted {@code coins} field
 * (wallets collection) is a SEPARATE balance that Node only DECRYPTS for read-only time estimates
 * (transaction.js L766/L1365); no canonical encrypted-coins WRITE was found in this class, so that
 * path is intentionally NOT ported yet. See PROMPT_D_STATUS.md.
 */
public interface WalletStore {

    /** userModel.findOne({_id},{wallet:1}) — Document with a numeric `wallet` (may be null/absent). */
    Document findUserWallet(Object userId);

    /** consultantModel.findOne({_id},{wallet:1, price:1}) — `wallet` + `price.platformShare` for gifts. */
    Document findConsultantWallet(Object consultantId);

    void setUserWallet(Object userId, double wallet);

    void setConsultantWallet(Object consultantId, double wallet);

    /** Atomic credit used by production Mongo; default keeps simple in-memory/unit stores compatible. */
    default boolean incrementUserWallet(Object userId, double amount) {
        Document wallet = findUserWallet(userId);
        if (wallet == null) return false;
        Object raw = wallet.get("wallet");
        double current = raw instanceof Number ? ((Number) raw).doubleValue() : 0;
        setUserWallet(userId, current + amount);
        return true;
    }

    /** Atomic consultant adjustment used by production Mongo. */
    default boolean incrementConsultantWallet(Object consultantId, double amount) {
        Document wallet = findConsultantWallet(consultantId);
        if (wallet == null) return false;
        Object raw = wallet.get("wallet");
        double current = raw instanceof Number ? ((Number) raw).doubleValue() : 0;
        setConsultantWallet(consultantId, current + amount);
        return true;
    }

    /**
     * Atomically debit the user only while the production-compatible balance guard still holds.
     * This closes the gap between the earlier read/validation and the eventual write.
     */
    default boolean debitUserWallet(Object userId, double coins, String transactionFor) {
        Document wallet = findUserWallet(userId);
        if (wallet == null) return false;
        Object raw = wallet.get("wallet");
        double current = raw instanceof Number ? ((Number) raw).doubleValue() : 0;
        if (transactionFor != null) {
            if ("consult".equals(transactionFor)) {
                double lhs = current == 0 ? 1 : current * 5;
                if (lhs < coins) return false;
            } else if (current == 0 || current < coins) {
                return false;
            }
        }
        setUserWallet(userId, current - coins);
        return true;
    }

    /** Node side-effect fired after a USER credit: extendOngoingSessionTime(userId, coins). Seam. */
    void extendOngoingSessionTime(Object userId, double coins);

    /** walletTransactionModel.create(payload) — the ledger row (transactionType 0 credit / 1 debit). */
    void insertLedger(Document payloadForWallet);
}
