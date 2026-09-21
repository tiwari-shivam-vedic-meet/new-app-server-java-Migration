package com.vedicmeet.appserver.payment;

import org.bson.Document;

import java.util.List;

/**
 * Port seam for {@link PaymentReconciliationService}. The gateway status calls
 * ({@code checkStatusPhonePaymentOrder} / {@code verifyPaytmTransaction}), the Meta purchase event, and
 * the wallet-crediting {@code confirmPayment} live behind here, so reconciliation is unit-testable and
 * fires nothing until wired + reviewed. {@code getPendingPayments} mirrors the Node batch query
 * (status INITIATED, meta not fired, under the retry limit, PhonePe/Paytm/legacy).
 */
public interface ReconciliationStore {

    List<Document> getPendingPayments();

    void incrementRetryCount(Object transactionId);

    PaymentReconciliationService.ReconStatus phonePeStatus(String orderId);

    PaymentReconciliationService.ReconStatus paytmStatus(String orderId);

    void markTransactionFailed(Object transactionId);

    /** TransactionService.confirmPayment({orderId, userId, paymentId||orderId}). */
    void confirmPayment(String orderId, String userId, String paymentId);

    /** freshTx.metaEventFired === true. */
    boolean isMetaEventFired(Object transactionId);

    /** Atomic markMetaEventFired(metaEventFired:false→true); false if another process claimed it. */
    boolean claimMetaEvent(Object transactionId);

    /** False keeps the transaction unclaimed until a real provider is configured. */
    default boolean isMetaPublisherReady() { return true; }

    void fireMetaPurchaseEvent(Document transaction);
}
