package com.vedicmeet.appserver.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of Node {@code queueInteraktEvent} (utils/classes/interakt-helper.js). Interakt events are NOT
 * sent to Interakt directly — they are queued through the Vedic Meet micro-service
 * ({@code POST ${MICRO_SERVICE_URL}/segmentation/interakt-event}); the micro-service holds the Interakt
 * credentials, so this Java client needs only the (config-driven) micro-service URL — no secrets here.
 *
 * SHADOW-ONLY: fire this from cron/re-engagement flows once they're wired.
 */
@Component
public class InteraktClient {

    private final String microServiceUrl;
    private final HttpJsonClient http;
    private final ObjectMapper mapper;

    public InteraktClient(@Value("${MICRO_SERVICE_URL:}") String microServiceUrl,
                          HttpJsonClient http, ObjectMapper mapper) {
        this.microServiceUrl = microServiceUrl;
        this.http = http;
        this.mapper = mapper;
    }

    /** Node payload: { eventType:'event', eventName, userId, phone, traits, eventProperties }. */
    public Map<String, Object> buildPayload(String eventName, String userId, String phone,
                                            Map<String, Object> eventProperties, Map<String, Object> traits) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("eventType", "event");
        p.put("eventName", eventName);
        p.put("userId", userId);
        p.put("phone", phone);
        p.put("traits", traits);
        p.put("eventProperties", eventProperties == null ? new LinkedHashMap<>() : eventProperties);
        return p;
    }

    public String endpoint() {
        return microServiceUrl + "/segmentation/interakt-event";
    }

    /** The cron/outbox worker must not claim jobs until the target service is configured. */
    public boolean isReady() {
        return microServiceUrl != null && !microServiceUrl.isBlank();
    }

    public void queueInteraktEvent(String eventName, String userId, String phone,
                                   Map<String, Object> eventProperties, Map<String, Object> traits) {
        if (!isReady()) throw new IllegalStateException("INTERAKT_NOT_CONFIGURED");
        try {
            String body = mapper.writeValueAsString(buildPayload(eventName, userId, phone, eventProperties, traits));
            http.post(endpoint(), new LinkedHashMap<>(), body);
        } catch (Exception e) {
            throw new RuntimeException("INTERAKT_QUEUE_FAILED", e);
        }
    }
}
