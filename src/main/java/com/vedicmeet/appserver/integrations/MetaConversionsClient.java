package com.vedicmeet.appserver.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Config-only Meta Conversions API adapter; no token or pixel identifier is stored in source. */
@Component
public class MetaConversionsClient {

    private final boolean enabled;
    private final String pixelId;
    private final String accessToken;
    private final String apiVersion;
    private final String testEventCode;
    private final HttpJsonClient http;
    private final ObjectMapper mapper;

    public MetaConversionsClient(
            @Value("${vedicmeet.integrations.meta.enabled:false}") boolean enabled,
            @Value("${vedicmeet.integrations.meta.pixel-id:}") String pixelId,
            @Value("${vedicmeet.integrations.meta.access-token:}") String accessToken,
            @Value("${vedicmeet.integrations.meta.api-version:v18.0}") String apiVersion,
            @Value("${vedicmeet.integrations.meta.test-event-code:}") String testEventCode,
            HttpJsonClient http, ObjectMapper mapper) {
        this.enabled = enabled;
        this.pixelId = pixelId;
        this.accessToken = accessToken;
        this.apiVersion = apiVersion;
        this.testEventCode = testEventCode;
        this.http = http;
        this.mapper = mapper;
    }

    public boolean isReady() {
        return enabled && present(pixelId) && present(accessToken);
    }

    /** Sends one Purchase event. The order id is the provider deduplication event_id. */
    public void purchase(Document transaction, String userIdentifier) {
        if (!isReady()) throw new IllegalStateException("META_CONVERSIONS_NOT_CONFIGURED");
        try {
            String orderId = text(transaction.get("orderId"));
            double value = number(transaction.get("paidAmount"));
            String currency = text(transaction.get("currency"));
            if (!present(currency)) currency = "INR";

            Map<String, Object> userData = new LinkedHashMap<>();
            if (present(userIdentifier)) {
                String normalized = userIdentifier.trim().toLowerCase(Locale.ROOT);
                userData.put(normalized.contains("@") ? "em" : "ph", hash(normalized));
                userData.put("external_id", hash(normalized));
            }

            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("value", value);
            customData.put("currency", currency.toUpperCase(Locale.ROOT));
            customData.put("order_id", orderId);
            customData.put("orderId", orderId);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_name", "Purchase");
            event.put("event_time", Instant.now().getEpochSecond());
            event.put("action_source", "system_generated");
            event.put("user_data", userData);
            event.put("custom_data", customData);
            if (present(orderId)) event.put("event_id", orderId);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("data", List.of(event));
            payload.put("access_token", accessToken);
            if (present(testEventCode)) payload.put("test_event_code", testEventCode);

            String response = http.post(endpoint(), Map.of("Content-Type", "application/json"),
                    mapper.writeValueAsString(payload));
            Map<String, Object> result = mapper.readValue(response,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            if (number(result.get("events_received")) <= 0) {
                throw new IllegalStateException("META_CONVERSIONS_REJECTED_EVENT");
            }
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception failure) {
            throw new IllegalStateException("META_CONVERSIONS_SEND_FAILED", failure);
        }
    }

    String endpoint() {
        return "https://graph.facebook.com/" + apiVersion + "/" + pixelId + "/events";
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("META_HASH_FAILED", impossible);
        }
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
