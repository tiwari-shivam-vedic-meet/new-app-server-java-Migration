package com.vedicmeet.appserver.payment;

import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * Port seam for {@link PaymentInitiateService}: the Paytm gateway order-create
 * ({@code initiatePaytmTransaction} — Node utils/classes/paytm.js). Needs Paytm merchant creds at
 * runtime. The production adapter fails closed when the explicitly configured provider is disabled
 * or incomplete; it never falls back to a production URL or embedded credential.
 */
public interface PaymentInitiateStore {

    Map<String, Object> initiatePaytmTransaction(Map<String, Object> paymentData);

    @Repository
    class Default implements PaymentInitiateStore {

        private final PaytmGatewayClient paytm;

        public Default(PaytmGatewayClient paytm) {
            this.paytm = paytm;
        }

        @Override
        public Map<String, Object> initiatePaytmTransaction(Map<String, Object> paymentData) {
            return paytm.initiate(paymentData);
        }
    }
}
