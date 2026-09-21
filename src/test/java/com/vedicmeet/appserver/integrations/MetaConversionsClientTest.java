package com.vedicmeet.appserver.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaConversionsClientTest {

    @Test
    void disabledClientDoesNotCallNetwork() {
        MetaConversionsClient client = new MetaConversionsClient(false, "pixel", "token", "v18.0", "",
                (url, headers, body) -> { throw new AssertionError("network called"); }, new ObjectMapper());
        assertFalse(client.isReady());
        assertThrows(IllegalStateException.class,
                () -> client.purchase(new Document("orderId", "O1"), "9000000000"));
    }

    @Test
    void purchaseUsesOrderAsEventIdAndHashesIdentity() {
        final String[] body = new String[1];
        MetaConversionsClient client = new MetaConversionsClient(true, "pixel", "token", "v18.0", "TEST",
                (url, headers, json) -> { body[0] = json; return "{\"events_received\":1}"; },
                new ObjectMapper());

        client.purchase(new Document("orderId", "ORDER-1").append("paidAmount", 100)
                .append("currency", "inr"), " User@Example.com ");

        assertTrue(client.isReady());
        assertTrue(body[0].contains("\"event_id\":\"ORDER-1\""));
        assertTrue(body[0].contains(MetaConversionsClient.hash("user@example.com")));
        assertFalse(body[0].contains("User@Example.com"));
    }

    @Test
    void providerMustAcknowledgeAnEvent() {
        MetaConversionsClient client = new MetaConversionsClient(true, "pixel", "token", "v18.0", "",
                (url, headers, body) -> "{\"events_received\":0}", new ObjectMapper());
        assertThrows(IllegalStateException.class,
                () -> client.purchase(new Document("orderId", "O1"), "9000000000"));
    }
}
