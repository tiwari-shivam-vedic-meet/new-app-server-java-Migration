package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⚠ SHADOW-ONLY / MONEY. FAITHFUL port of Node {@code TransactionService.confirmPayment}
 * (node-production-bf308ee utils/classes/transaction.js L519-606) — the shared payment-confirm the
 * PhonePe/Paytm callbacks and the app {@code /confirm} route all funnel through.
 *
 * <p>The atomicity the review flagged is exactly reproduced: (1) the order must exist; (2) if already
 * {@code COMPLETED}/{@code paid} → success with NO re-credit; (3) an ATOMIC
 * {@code INITIATED → COMPLETED} transition decides the single winner — a loser (null) also returns
 * success but does NOT credit; (4) only the winner credits the wallet (via the tested
 * {@code creditAndDebitOnWallet}) and runs recharge-coupon rewards. The complete operation is a Mongo
 * transaction, so a wallet/ledger failure rolls the status claim back to INITIATED. Duplicate deliveries
 * still cannot double-credit because only one INITIATED transaction can be claimed.</p>
 *
 * <p>Still gated: reached only through {@code @MigrationWrite} endpoints (writes off by default), so it
 * does not move money in a shadow launch until enabled + reviewed.</p>
 */
@Service
public class PaymentConfirmService {

    private final PaymentConfirmStore store;

    public PaymentConfirmService(PaymentConfirmStore store) {
        this.store = store;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public GatewayStore.ConfirmResult confirmPayment(String orderId, String paymentId, boolean eventQueuedFired) {
        Document tx = store.findTransactionByOrderId(orderId);
        if (tx == null) {
            throw new IllegalStateException("Order is not present or this order id and user");
        }

        String status = tx.getString("status");
        if ("COMPLETED".equals(status) || "paid".equals(status)) {
            return new GatewayStore.ConfirmResult(true, "Payment confirmed successfully");
        }

        boolean metaEventFired = false;
        long previousPayment = store.countPreviousPaid(tx.get("userId"));
        if (previousPayment < 1 && !Boolean.TRUE.equals(tx.get("metaEventFired"))) {
            metaEventFired = true; // Meta purchase-event call itself is commented out in Node
        }

        // Atomic INITIATED -> COMPLETED: only the winner proceeds to credit.
        Document updated = store.atomicComplete(orderId, paymentId, metaEventFired, eventQueuedFired);
        if (updated == null) {
            return new GatewayStore.ConfirmResult(true, "Payment confirmed successfully");
        }

        store.creditUserForConfirm(updated, paymentId);
        store.handleRechargeCouponRewards(updated);

        return new GatewayStore.ConfirmResult(true, "Payment confirmed successfully");
    }
}
