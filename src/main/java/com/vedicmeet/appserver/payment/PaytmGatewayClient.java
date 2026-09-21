package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.pg.merchant.PaytmChecksum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Paytm initiate/status/checksum adapter. Provider calls are disabled unless every required TEST
 * setting is explicitly supplied. Callback payloads are never trusted as proof of payment: callers
 * must also require {@link #status(String)} to return SUCCESS before crediting a wallet.
 */
@Component
public class PaytmGatewayClient {

    private final PaymentHttpTransport http;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String merchantId;
    private final String merchantKey;
    private final String baseUrl;
    private final String callbackUrl;
    private final String websiteName;

    public PaytmGatewayClient(
            PaymentHttpTransport http,
            ObjectMapper mapper,
            @Value("${vedicmeet.payment.paytm.enabled:false}") boolean enabled,
            @Value("${vedicmeet.payment.paytm.merchant-id:}") String merchantId,
            @Value("${vedicmeet.payment.paytm.merchant-key:}") String merchantKey,
            @Value("${vedicmeet.payment.paytm.base-url:}") String baseUrl,
            @Value("${vedicmeet.payment.paytm.callback-url:}") String callbackUrl,
            @Value("${vedicmeet.payment.paytm.website-name:}") String websiteName) {
        this.http = http;
        this.mapper = mapper;
        this.enabled = enabled;
        this.merchantId = merchantId;
        this.merchantKey = merchantKey;
        this.baseUrl = baseUrl;
        this.callbackUrl = callbackUrl;
        this.websiteName = websiteName;
    }

    public Map<String, Object> initiate(Map<String, Object> options) {
        try {
            requireConfigured(true);
            String orderId = string(options == null ? null : options.get("orderId"));
            String customerId = string(options == null ? null : options.get("customerId"));
            double amount = number(options == null ? null : options.get("amount"));
            if (!present(orderId)) throw new IllegalArgumentException("Order ID is required for Paytm transaction initiation");
            if (!present(customerId)) throw new IllegalArgumentException("Customer ID is required for Paytm transaction initiation");
            if (amount <= 0) throw new IllegalArgumentException("Valid amount is required for Paytm transaction initiation");

            Map<String, Object> userInfo = new LinkedHashMap<>();
            userInfo.put("custId", customerId);
            putIfPresent(userInfo, "mobile", options.get("mobileNumber"));
            putIfPresent(userInfo, "email", options.get("email"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestType", "Payment");
            body.put("mid", merchantId);
            body.put("websiteName", websiteName);
            body.put("orderId", orderId);
            body.put("callbackUrl", callbackUrl);
            body.put("txnAmount", Map.of("value", String.format(Locale.ROOT, "%.2f", amount), "currency", "INR"));
            body.put("userInfo", userInfo);

            String bodyJson = mapper.writeValueAsString(body);
            String signature = PaytmChecksum.generateSignature(bodyJson, merchantKey);
            String requestJson = mapper.writeValueAsString(Map.of(
                    "body", body,
                    "head", Map.of("signature", signature)));
            String url = stripTrailingSlash(baseUrl) + "/theia/api/v1/initiateTransaction?mid="
                    + encode(merchantId) + "&orderId=" + encode(orderId);
            PaymentHttpTransport.Response response = http.exchange("POST", url, Map.of(),
                    "application/json", requestJson);
            if (!response.is2xx()) return failure("Paytm initiation HTTP " + response.statusCode);

            Map<String, Object> root = json(response.body);
            Map<String, Object> responseBody = map(root.get("body"));
            Map<String, Object> resultInfo = map(responseBody.get("resultInfo"));
            if ("F".equals(string(resultInfo.get("resultStatus")))) {
                return failure(firstPresent(string(resultInfo.get("resultMsg")), "Payment initiation failed"));
            }
            String token = string(responseBody.get("txnToken"));
            if (!present(token)) return failure(firstPresent(string(resultInfo.get("resultMsg")),
                    "Transaction token not received from Paytm"));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderId", orderId);
            data.put("txnToken", token);
            data.put("mid", merchantId);
            data.put("amount", String.format(Locale.ROOT, "%.2f", amount));
            data.put("callbackUrl", callbackUrl);
            return Map.of("success", true, "data", data);
        } catch (Exception e) {
            return failure(safeMessage(e, "Paytm initiation failed"));
        }
    }

    public PaymentReconciliationService.ReconStatus status(String orderId) {
        if (!present(orderId)) return PaymentReconciliationService.ReconStatus.ERROR;
        try {
            requireConfigured(false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mid", merchantId);
            body.put("orderId", orderId);
            String bodyJson = mapper.writeValueAsString(body);
            String signature = PaytmChecksum.generateSignature(bodyJson, merchantKey);
            String request = mapper.writeValueAsString(Map.of(
                    "body", body,
                    "head", Map.of("signature", signature)));
            PaymentHttpTransport.Response response = http.exchange("POST",
                    stripTrailingSlash(baseUrl) + "/v3/order/status", Map.of(),
                    "application/json", request);
            if (!response.is2xx()) return PaymentReconciliationService.ReconStatus.ERROR;

            Map<String, Object> responseBody = map(json(response.body).get("body"));
            Map<String, Object> resultInfo = map(responseBody.get("resultInfo"));
            String status = upper(resultInfo.get("resultStatus"));
            String code = string(resultInfo.get("resultCode"));
            if ("TXN_SUCCESS".equals(status) || "01".equals(code)) {
                return PaymentReconciliationService.ReconStatus.SUCCESS;
            }
            if ("PENDING".equals(status) || "402".equals(code)) {
                return PaymentReconciliationService.ReconStatus.PENDING;
            }
            return PaymentReconciliationService.ReconStatus.FAILED;
        } catch (Exception e) {
            return PaymentReconciliationService.ReconStatus.ERROR;
        }
    }

    /** Verify the provider checksum on the callback fields before any status lookup or write. */
    public boolean verifyCallback(Map<String, Object> callback) {
        if (!configured(false) || callback == null || callback.isEmpty()) return false;
        try {
            Map<String, Object> flattened = new LinkedHashMap<>(callback);
            if (callback.get("body") instanceof Map) flattened.putAll(map(callback.get("body")));
            String checksum = removeChecksum(flattened);
            if (!present(checksum)) return false;
            TreeMap<String, String> fields = new TreeMap<>();
            flattened.forEach((key, value) -> {
                if (value != null && !(value instanceof Map) && !(value instanceof Iterable<?>)) {
                    fields.put(key, String.valueOf(value));
                }
            });
            return PaytmChecksum.verifySignature(fields, merchantKey, checksum);
        } catch (Exception e) {
            return false;
        }
    }

    private void requireConfigured(boolean requireCallback) {
        if (!configured(requireCallback)) throw new IllegalStateException("PAYTM_NOT_CONFIGURED");
    }

    private boolean configured(boolean requireCallback) {
        return enabled && present(merchantId) && present(merchantKey) && present(baseUrl)
                && (!requireCallback || (present(callbackUrl) && present(websiteName)));
    }

    private String removeChecksum(Map<String, Object> fields) {
        for (String key : new java.util.ArrayList<>(fields.keySet())) {
            if ("CHECKSUMHASH".equalsIgnoreCase(key) || "checkSumHash".equalsIgnoreCase(key)) {
                Object value = fields.remove(key);
                return string(value);
            }
        }
        return null;
    }

    private Map<String, Object> failure(String message) {
        return Map.of("success", false, "data", Map.of("message", message));
    }

    private Map<String, Object> json(String value) throws Exception {
        return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, String.valueOf(value).trim());
    }

    private double number(Object value) {
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0; }
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String stripTrailingSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private String upper(Object value) { String s = string(value); return s == null ? "" : s.toUpperCase(Locale.ROOT); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    private String firstPresent(String value, String fallback) { return present(value) ? value : fallback; }
    private String safeMessage(Exception e, String fallback) {
        String message = e.getMessage();
        return present(message) && !message.toLowerCase(Locale.ROOT).contains("secret") ? message : fallback;
    }
}
