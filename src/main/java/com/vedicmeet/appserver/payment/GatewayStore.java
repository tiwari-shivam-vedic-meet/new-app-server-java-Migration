package com.vedicmeet.appserver.payment;

import java.util.List;

/**
 * Port seam for {@link GatewayService}. Reads the active-gateway master setting and performs the shared
 * {@code confirmPayment} (the wallet-crediting money core), which stays unwired until human review.
 */
public interface GatewayStore {

    /** {@code seedMaster({for:'userMasterSettings'}).data.activePaymentGateways ?? .activePaymentGateways}; null if unset. */
    List<String> readActivePaymentGatewaysRaw();

    /** {@code TransactionService.confirmPayment({orderId, paymentId})} — idempotent wallet credit. */
    ConfirmResult confirmPayment(String orderId, String paymentId);

    final class ConfirmResult {
        public final boolean success;
        public final String message;

        public ConfirmResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
