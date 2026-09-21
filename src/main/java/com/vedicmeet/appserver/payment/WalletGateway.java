package com.vedicmeet.appserver.payment;

import org.bson.Document;

import java.util.Map;

/**
 * Port for the wallet/transaction DB operations the Razorpay webhook-confirm flow needs
 * (Node utils/classes/transaction.js confirmPaymentWithWebhook + creditAndDebitOnWallet).
 *
 * Abstracting these behind a port keeps {@link PaymentWebhookService}'s idempotency logic
 * deterministically unit-testable (a fake gateway in tests), which is essential for money code —
 * and it isolates the exact set of DB effects a reviewer must audit.
 *
 * The production implementation is {@link MongoWalletGateway}.
 */
public interface WalletGateway {

    /** Always-on audit: persist the raw webhook payload (Node saves this FIRST, before any checks). */
    void saveWebhookAudit(Map<String, Object> requestData);

    /** transactionModel.findOne({ orderId }). Null if absent. */
    Document findTransactionByOrderId(String orderId);

    /**
     * The idempotency guard. Atomic {@code findOneAndUpdate({_id, status:'INITIATED'},
     * {$set:{paymentId, status:'COMPLETED'}}, {new:true})}. Returns the updated tx, or null when
     * the tx was not INITIATED (i.e. another webhook already won) — the caller must then NOT credit.
     */
    Document atomicCompleteInitiated(Object transactionId, String paymentId);

    /** Atomically marks only an INITIATED transaction FAILED; false means another path won. */
    boolean atomicFailInitiated(Object transactionId, String paymentId);

    /** userModel.findOne({_id}). */
    Document findUser(Object userId);

    /**
     * Atomically increment the user's plain `wallet` and return the new balance. The atomic increment
     * is required because two different successful orders for the same user can complete together.
     */
    double creditUserWallet(Object userId, double coins);

    /** walletTransactionModel.create(payload) — the ledger row (transactionType 0 = credit). */
    void insertWalletLedger(Document payloadForWallet);
}
