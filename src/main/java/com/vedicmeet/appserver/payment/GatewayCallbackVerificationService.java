package com.vedicmeet.appserver.payment;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Security boundary for public PhonePe and Paytm callbacks.
 *
 * <p>A callback body is only a notification. It is never accepted as proof that money was received:
 * PhonePe must independently report SUCCESS for the merchant order; Paytm must first pass its
 * provider checksum and then independently report SUCCESS. Missing configuration and provider
 * errors fail closed.</p>
 */
@Service
public class GatewayCallbackVerificationService {

    private final PhonePeGatewayClient phonePe;
    private final PaytmGatewayClient paytm;

    public GatewayCallbackVerificationService(PhonePeGatewayClient phonePe, PaytmGatewayClient paytm) {
        this.phonePe = phonePe;
        this.paytm = paytm;
    }

    public Verification verifyPhonePe(Map<String, Object> callback) {
        if (callback == null || !"checkout.order.completed".equals(callback.get("event"))) {
            return Verification.rejected("PhonePe callback event is not supported");
        }
        Map<String, Object> payload = asMap(callback.get("payload"));
        String orderId = firstPresent(payload.get("merchantOrderId"), payload.get("orderId"));
        if (!present(orderId)) return Verification.rejected("PhonePe callback order ID is missing");
        if (phonePe.status(orderId) != PaymentReconciliationService.ReconStatus.SUCCESS) {
            return Verification.rejected("PhonePe payment is not confirmed by the provider");
        }
        return Verification.accepted(payload);
    }

    public Verification verifyPaytm(Map<String, Object> callback) {
        if (callback == null || callback.isEmpty() || !paytm.verifyCallback(callback)) {
            return Verification.rejected("Invalid Paytm callback checksum");
        }
        Map<String, Object> flattened = new LinkedHashMap<>(callback);
        flattened.putAll(asMap(callback.get("body")));
        String orderId = firstPresent(
                flattened.get("ORDERID"), flattened.get("orderId"), flattened.get("ORDER_ID"));
        if (!present(orderId)) return Verification.rejected("Paytm callback order ID is missing");
        if (paytm.status(orderId) != PaymentReconciliationService.ReconStatus.SUCCESS) {
            return Verification.rejected("Paytm payment is not confirmed by the provider");
        }
        return Verification.accepted(callback);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private String firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record Verification(boolean verified, String message, Map<String, Object> payload) {
        static Verification accepted(Map<String, Object> payload) {
            return new Verification(true, null, payload);
        }

        static Verification rejected(String message) {
            return new Verification(false, message, Map.of());
        }
    }
}
