package com.vedicmeet.appserver.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApnsVoipPushClientTest {

    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void send_usesPushKitHeadersAndNodeCompatiblePayload() throws Exception {
        Path key = signingKey();
        FakeHttp http = new FakeHttp();
        ApnsVoipPushClient client = new ApnsVoipPushClient(mapper, http, true,
                key.toString(), "TEAM_TEST", "KEY_TEST", "user.bundle.voip",
                "consultant.bundle.voip", "https://api.sandbox.push.apple.com",
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC));
        Map<String, Object> payload = Map.of(
                "callId", "room-1", "callerName", "A", "callerImage", "x",
                "sessionId", "room-1", "callMode", "audio");

        assertTrue(client.isReady());
        assertTrue(client.send("cons", "cons", payload));

        assertEquals("https://api.sandbox.push.apple.com/3/device/cons", http.url);
        assertEquals("consultant.bundle.voip", http.headers.get("apns-topic"));
        assertEquals("voip", http.headers.get("apns-push-type"));
        assertEquals("10", http.headers.get("apns-priority"));
        assertTrue(http.headers.get("authorization").startsWith("bearer "));
        assertEquals(3, http.headers.get("authorization").substring(7).split("\\.").length);
        JsonNode body = mapper.readTree(http.body);
        assertEquals(1, body.path("aps").path("content-available").asInt());
        assertEquals("room-1", body.path("callId").asText());
        assertEquals("audio", body.path("callData").path("callMode").asText());
    }

    @Test
    void disabled_failsClosedWithoutNetwork() {
        FakeHttp http = new FakeHttp();
        ApnsVoipPushClient client = new ApnsVoipPushClient(mapper, http, false,
                "missing.p8", "", "", "", "", "https://sandbox", Clock.systemUTC());
        assertFalse(client.isReady());
        assertFalse(client.send("token", "user", Map.of()));
        assertNull(http.url);
    }

    private Path signingKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] key = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key)
                + "\n-----END PRIVATE KEY-----\n";
        Path file = tempDir.resolve("test-signing-key.p8");
        Files.writeString(file, pem);
        return file;
    }

    private static final class FakeHttp implements NotificationHttpTransport {
        String url;
        Map<String, String> headers;
        String body;
        @Override public Response exchange(String method, String url, Map<String, String> headers,
                                            String contentType, String body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
            return new Response(200, "{}");
        }
    }
}
