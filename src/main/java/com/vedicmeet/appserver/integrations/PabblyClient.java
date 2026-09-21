package com.vedicmeet.appserver.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Config-only replacement for the Pabbly webhook URL embedded in Node jobs.js.
 * The URL is a credential-like capability and must never be committed or logged.
 */
@Component
public class PabblyClient {

    private final boolean enabled;
    private final String webhookUrl;
    private final HttpJsonClient http;
    private final ObjectMapper mapper;

    public PabblyClient(@Value("${vedicmeet.integrations.pabbly.enabled:false}") boolean enabled,
                        @Value("${vedicmeet.integrations.pabbly.webhook-url:}") String webhookUrl,
                        HttpJsonClient http, ObjectMapper mapper) {
        this.enabled = enabled;
        this.webhookUrl = webhookUrl;
        this.http = http;
        this.mapper = mapper;
    }

    public boolean isReady() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    public void send(Map<String, Object> payload) {
        if (!isReady()) throw new IllegalStateException("PABBLY_NOT_CONFIGURED");
        try {
            http.post(webhookUrl, Map.of("Content-Type", "application/json"),
                    mapper.writeValueAsString(payload == null ? Map.of() : payload));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("PABBLY_PAYLOAD_FAILED", error);
        }
    }
}
