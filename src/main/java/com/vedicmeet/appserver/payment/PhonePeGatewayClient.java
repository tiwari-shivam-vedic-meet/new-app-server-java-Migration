package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PhonePe OAuth + order-status adapter matching the production Node v2 checkout integration.
 * No provider URL or credential has a production default; missing configuration fails closed.
 */
@Component
public class PhonePeGatewayClient {

    private final PaymentHttpTransport http;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String clientId;
    private final String clientSecret;
    private final String authUrl;
    private final String statusBaseUrl;

    public PhonePeGatewayClient(
            PaymentHttpTransport http,
            ObjectMapper mapper,
            @Value("${vedicmeet.payment.phonepe.enabled:false}") boolean enabled,
            @Value("${vedicmeet.payment.phonepe.client-id:}") String clientId,
            @Value("${vedicmeet.payment.phonepe.client-secret:}") String clientSecret,
            @Value("${vedicmeet.payment.phonepe.auth-url:}") String authUrl,
            @Value("${vedicmeet.payment.phonepe.status-base-url:}") String statusBaseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.enabled = enabled;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authUrl = authUrl;
        this.statusBaseUrl = statusBaseUrl;
    }

    public PaymentReconciliationService.ReconStatus status(String merchantOrderId) {
        if (!configured() || merchantOrderId == null || merchantOrderId.isBlank()) {
            return PaymentReconciliationService.ReconStatus.ERROR;
        }
        try {
            String accessToken = accessToken();
            String url = stripTrailingSlash(statusBaseUrl) + "/" + encodePath(merchantOrderId) + "/status";
            PaymentHttpTransport.Response response = http.exchange("GET", url,
                    Map.of("Authorization", "O-Bearer " + accessToken),
                    "application/json", null);
            if (!response.is2xx()) return response.statusCode >= 500
                    ? PaymentReconciliationService.ReconStatus.ERROR
                    : PaymentReconciliationService.ReconStatus.FAILED;
            Map<String, Object> payload = json(response.body);
            String state = string(payload.get("state"));
            if ("COMPLETED".equalsIgnoreCase(state) || "PAID".equalsIgnoreCase(state)) {
                return PaymentReconciliationService.ReconStatus.SUCCESS;
            }
            if ("FAILED".equalsIgnoreCase(state)) return PaymentReconciliationService.ReconStatus.FAILED;
            if ("PENDING".equalsIgnoreCase(state)) return PaymentReconciliationService.ReconStatus.PENDING;
            return PaymentReconciliationService.ReconStatus.ERROR;
        } catch (RuntimeException e) {
            return PaymentReconciliationService.ReconStatus.ERROR;
        }
    }

    private String accessToken() {
        String form = "client_version=1&grant_type=client_credentials&client_id="
                + formEncode(clientId) + "&client_secret=" + formEncode(clientSecret);
        PaymentHttpTransport.Response response = http.exchange("POST", authUrl, Map.of(),
                "application/x-www-form-urlencoded", form);
        if (!response.is2xx()) throw new IllegalStateException("PHONEPE_AUTH_FAILED");
        String token = string(json(response.body).get("access_token"));
        if (token == null || token.isBlank()) throw new IllegalStateException("PHONEPE_TOKEN_MISSING");
        return token;
    }

    private boolean configured() {
        return enabled && present(clientId) && present(clientSecret) && present(authUrl) && present(statusBaseUrl);
    }

    private Map<String, Object> json(String raw) {
        try {
            return mapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("PHONEPE_INVALID_RESPONSE", e);
        }
    }

    private String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return formEncode(value).replace("+", "%20");
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
}
