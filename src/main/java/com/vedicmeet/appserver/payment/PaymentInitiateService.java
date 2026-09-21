package com.vedicmeet.appserver.payment;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ⚠ SHADOW-ONLY / MONEY. FAITHFUL port of the payment-initiate flows from CURRENT production
 * (node-production-bf308ee): {@code phonePeInitiatePayment} (razorpay.js L53) and
 * {@code paytmInitiatePayment} (transaction.js L2781).
 *
 * <p><b>PhonePe is DISABLED in production</b> — Node throws immediately at the top of the method
 * ("Phonepe is not working…"), making the rest dead code; reproduced faithfully. Paytm-initiate is
 * functional: it validates a 10-digit mobile or an email (with the {@code user<mobile>@vedicmeet.com}
 * fallback), builds the payment payload, and delegates the gateway order-create to
 * {@link PaymentInitiateStore#initiatePaytmTransaction} (which needs Paytm merchant creds — a seam).</p>
 */
@Service
public class PaymentInitiateService {

    private static final Pattern MOBILE = Pattern.compile("^\\d{10}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final PaymentInitiateStore store;

    public PaymentInitiateService(PaymentInitiateStore store) {
        this.store = store;
    }

    /** Node phonePeInitiatePayment: disabled — always throws (the rest of the method is dead code). */
    public Map<String, Object> phonePeInitiatePayment(Map<String, Object> input) {
        throw new IllegalStateException("Phonepe is not working.Please Try Some another Payment Method");
    }

    /** Node paytmInitiatePayment: validate mobile/email, build payload, delegate the gateway call. */
    public Map<String, Object> paytmInitiatePayment(Map<String, Object> input) {
        Object amountRaw = input == null ? null : input.get("amount");
        Object amount = amountRaw != null ? amountRaw : 100; // input.amount || 100
        String mobileNumber = trim(str(input, "mobileNumber"));
        String email = trim(str(input, "email"));
        String merchantOrderId = str(input, "merchantOrderId");
        if (merchantOrderId == null) merchantOrderId = "";

        boolean hasValidMobile = !mobileNumber.isEmpty() && MOBILE.matcher(mobileNumber).matches();
        boolean hasValidEmail = !email.isEmpty() && EMAIL.matcher(email).matches();

        if (hasValidMobile && !hasValidEmail) {
            email = "user" + mobileNumber + "@vedicmeet.com";
            hasValidEmail = true;
        }

        if (!hasValidMobile && !hasValidEmail) {
            throw new IllegalArgumentException(
                    "Paytm requires either a valid mobile number (10 digits) or a valid email address. Please provide at least one.");
        }

        String customerId = "CUST_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Map<String, Object> paymentData = new LinkedHashMap<>();
        paymentData.put("orderId", merchantOrderId);
        paymentData.put("customerId", customerId);
        paymentData.put("amount", amount);
        paymentData.put("mobileNumber", hasValidMobile ? mobileNumber : "");
        paymentData.put("email", hasValidEmail ? email : "");

        Map<String, Object> paytmResponse = store.initiatePaytmTransaction(paymentData);

        if (paytmResponse != null && !truthy(paytmResponse.get("success"))) {
            throw new IllegalStateException(dataMessage(paytmResponse,
                    "Paytm payment initiation failed. Please try another payment method."));
        }
        if (paytmResponse != null && Boolean.TRUE.equals(paytmResponse.get("success"))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("data", paytmResponse.get("data"));
            return r;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", dataMessage(paytmResponse, "Payment initiation failed!"));
        return r;
    }

    @SuppressWarnings("unchecked")
    private String dataMessage(Map<String, Object> resp, String fallback) {
        if (resp != null && resp.get("data") instanceof Map) {
            Object m = ((Map<String, Object>) resp.get("data")).get("message");
            if (m != null) return String.valueOf(m);
        }
        return fallback;
    }

    private boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        return true;
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
