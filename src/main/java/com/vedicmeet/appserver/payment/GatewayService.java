package com.vedicmeet.appserver.payment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * ⚠ SHADOW-ONLY / MONEY. FAITHFUL port of the payment-gateway surface from CURRENT production
 * (node-production-bf308ee): {@code getActivePaymentGateways} + {@code phonePeCallback}
 * (utils/classes/razorpay.js L100) + {@code paytmCallback} (utils/classes/transaction.js L2839).
 *
 * <p>Both callbacks are thin adapters that, on a success state, delegate to the shared
 * {@code confirmPayment(orderId, paymentId)} (which credits the wallet) — kept behind
 * {@link GatewayStore#confirmPayment} so the money credit stays unwired until human review. The
 * decision logic (state/status branches, orderId resolution, gateway filtering) is ported exactly and
 * unit-tested.</p>
 */
@Service
public class GatewayService {

    private static final List<String> VALID = List.of("paytm", "phonepe", "razorpay");
    private static final List<String> DEFAULT = List.of("paytm", "phonepe", "razorpay");

    private final GatewayStore store;

    public GatewayService(GatewayStore store) {
        this.store = store;
    }

    /** getActivePaymentGateways: seedMaster data.activePaymentGateways ?? activePaymentGateways ?? DEFAULT. */
    public List<String> getActivePaymentGateways() {
        List<String> raw = store.readActivePaymentGatewaysRaw();
        List<String> source = (raw != null) ? raw : DEFAULT;
        List<String> gateways = source.stream()
                .map(g -> String.valueOf(g).toLowerCase())
                .filter(VALID::contains)
                .collect(Collectors.toList());
        return gateways.isEmpty() ? DEFAULT : gateways;
    }

    /** phonePeCallback(payload): state COMPLETED → confirmPayment(merchantOrderId, paymentDetails[0].transactionId). */
    public Map<String, Object> phonePeCallback(Map<String, Object> payload) {
        if (payload != null && "COMPLETED".equals(payload.get("state"))) {
            String orderId = str(payload.get("merchantOrderId"));
            String paymentId = firstTransactionId(payload.get("paymentDetails"));
            GatewayStore.ConfirmResult confirm = store.confirmPayment(orderId, paymentId);
            return ok(confirm.success, confirm.message != null ? confirm.message : "Payment confirmed successfully");
        }
        return ok(false, "Payment confirmation failed!");
    }

    /** paytmCallback(input): resolve orderId, branch on STATUS/RESPCODE, credit on success. */
    public Map<String, Object> paytmCallback(Map<String, Object> input) {
        Map<String, Object> data = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        Object body = data.get("body");
        if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = (Map<String, Object>) body;
            data.putAll(bodyMap); // { ...input, ...input.body }
        }

        String orderId = firstNonNull(data.get("ORDERID"), data.get("orderId"), data.get("ORDER_ID"));
        if (orderId == null && body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = (Map<String, Object>) body;
            orderId = firstNonNull(bodyMap.get("ORDERID"), bodyMap.get("orderId"));
        }
        if (orderId == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Order ID is required for payment verification");
            r.put("data", new LinkedHashMap<>());
            return r;
        }

        String resultStatus = str(data.get("STATUS"));
        String resultCode = str(data.get("RESPCODE"));
        String txnId = str(data.get("TXNID"));
        Object txnAmount = data.get("TXNAMOUNT");
        String respMsg = str(data.get("RESPMSG"));

        if ("TXN_SUCCESS".equals(resultStatus) || "01".equals(resultCode)) {
            GatewayStore.ConfirmResult confirm = store.confirmPayment(orderId, txnId);
            return ok(confirm.success, confirm.message != null ? confirm.message : "Payment confirmed successfully");
        } else if ("PENDING".equals(resultStatus) || "402".equals(resultCode)) {
            return failWithData(respMsg != null ? respMsg : "Payment is pending bank confirmation",
                    orderId, txnId, txnAmount, "PENDING", resultStatus, resultCode, respMsg);
        } else {
            return failWithData(respMsg != null ? respMsg : "Transaction verification failed",
                    orderId, txnId, txnAmount, "FAILED", resultStatus, resultCode, respMsg);
        }
    }

    private Map<String, Object> ok(boolean success, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", success);
        r.put("message", message);
        return r;
    }

    private Map<String, Object> failWithData(String message, String orderId, String txnId, Object txnAmount,
                                             String status, String resultStatus, String resultCode, String respMsg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("txnId", txnId);
        data.put("txnAmount", txnAmount);
        data.put("status", status);
        data.put("resultStatus", resultStatus);
        data.put("resultCode", resultCode);
        data.put("resultMsg", respMsg);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", message);
        r.put("data", data);
        return r;
    }

    @SuppressWarnings("unchecked")
    private String firstTransactionId(Object paymentDetails) {
        if (paymentDetails instanceof List && !((List<?>) paymentDetails).isEmpty()) {
            Object first = ((List<Object>) paymentDetails).get(0);
            if (first instanceof Map) {
                return str(((Map<String, Object>) first).get("transactionId"));
            }
        }
        return null;
    }

    private String firstNonNull(Object... vals) {
        return Arrays.stream(vals).filter(v -> v != null && !String.valueOf(v).isEmpty())
                .map(String::valueOf).findFirst().orElse(null);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
