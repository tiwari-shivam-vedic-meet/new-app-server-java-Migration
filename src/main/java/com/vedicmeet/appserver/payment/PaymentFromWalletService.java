package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⚠ SHADOW-ONLY — HUMAN REVIEW REQUIRED. Faithful port of Node
 * {@code TransactionService.paymentFromWallet} (utils/classes/transaction.js L678): spends the
 * caller's wallet on a consult/gift/topup, credits the consultant for consult/gift, drops a gift
 * comment when a broadcast is involved, and nudges the waitlist. Orchestrates the already-ported
 * {@link WalletService} credit/debit. NOT wired to any route.
 */
@Service
public class PaymentFromWalletService {

    private final WalletService wallet;
    private final PaymentFromWalletStore store;

    public PaymentFromWalletService(WalletService wallet, PaymentFromWalletStore store) {
        this.wallet = wallet;
        this.store = store;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void paymentFromWallet(Document input, Document userObj) {
        Object userId = userObj.get("_id");

        Object meta = input.get("meta");
        if (meta instanceof String) {
            input.put("meta", Document.parse((String) meta));
        }

        // Can't spend while a call is live.
        if (store.isUserOnCall(userId)) {
            throw new RuntimeException("You are on call in progress, please try after call ends");
        }

        input.put("adminCommision", store.getAdminCommission());
        input.put("userId", userId);

        // Debit the user's wallet (WalletService applies the consult low-balance guard).
        wallet.creditAndDebitOnWallet("user", "debit", input);

        String transactionFor = input.getString("transactionFor");
        if ("consult".equals(transactionFor) || "gift".equals(transactionFor)) {
            input.put("userId", userId);
            wallet.creditAndDebitOnWallet("cons", "credit", input);

            if (input.get("broadcastId") != null) {
                String name = userObj.getString("name");
                String reason = input.get("walletDeductReason") == null ? "" : String.valueOf(input.get("walletDeductReason"));
                store.insertGiftComment(input.get("broadcastId"), userId, input.get("giftId"),
                        (name == null ? "null" : name) + " gifted " + reason);
            }
        }

        store.maybeUpdateWaitlist(userId);
    }
}
