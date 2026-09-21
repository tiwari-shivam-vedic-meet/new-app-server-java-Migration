package com.vedicmeet.appserver.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveKitServerProviderTest {

    @Test
    void participantTokenUsesOfficialJwtClaimsWithoutNetworkCall() throws Exception {
        LiveKitServerProvider provider = new LiveKitServerProvider(true, "wss://test.livekit.invalid",
                "test-key", "test-secret-with-enough-length", 10,
                "", "", "ap-south-1", "test-bucket");

        Map<String, Object> result = provider.participantToken("room-1", "consultant-1", true);

        assertEquals("room-1", result.get("roomName"));
        assertEquals("consultant-1", result.get("identity"));
        String jwt = String.valueOf(result.get("token"));
        JsonNode payload = new ObjectMapper().readTree(new String(
                Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
        assertEquals("consultant-1", payload.path("sub").asText());
        assertTrue(payload.path("video").path("roomJoin").asBoolean());
        assertEquals("room-1", payload.path("video").path("room").asText());
        assertTrue(payload.path("video").path("canPublish").asBoolean());
        assertTrue(payload.path("video").path("canSubscribe").asBoolean());
    }

    @Test
    void disabledProviderFailsClosedBeforeAnyNetworkCall() {
        LiveKitServerProvider provider = new LiveKitServerProvider(false, "", "", "", 10,
                "", "", "", "");
        assertFalse(provider.isReady());
        assertEquals("LIVEKIT_NOT_CONFIGURED", assertThrows(IllegalStateException.class,
                () -> provider.participantToken("room", "user", true)).getMessage());
    }
}
