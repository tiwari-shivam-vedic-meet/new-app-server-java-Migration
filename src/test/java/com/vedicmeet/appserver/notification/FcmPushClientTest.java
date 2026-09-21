package com.vedicmeet.appserver.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FcmPushClientTest {

    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void send_getsOAuthTokenThenPostsToCorrectFirebaseProject_andCachesToken() throws Exception {
        Path credentials = serviceAccountFile();
        FakeHttp http = new FakeHttp();
        http.responses.add(new NotificationHttpTransport.Response(200,
                "{\"access_token\":\"local-test-token\",\"expires_in\":3600}"));
        http.responses.add(new NotificationHttpTransport.Response(200, "{\"name\":\"message/1\"}"));
        http.responses.add(new NotificationHttpTransport.Response(200, "{\"name\":\"message/2\"}"));
        FcmPushClient client = new FcmPushClient(mapper, http, true,
                credentials.toString(), credentials.toString(),
                "https://oauth.test/token", "https://fcm.test",
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC));

        assertTrue(client.isReady());
        assertTrue(client.send("user", Map.of("message", Map.of("token", "device-1"))));
        assertTrue(client.send("user", Map.of("message", Map.of("token", "device-2"))));

        assertEquals(3, http.requests.size(), "one OAuth call plus two sends");
        assertEquals("https://oauth.test/token", http.requests.get(0).url);
        assertTrue(http.requests.get(0).body.contains("assertion="));
        assertEquals("https://fcm.test/v1/projects/test-project/messages:send", http.requests.get(1).url);
        assertEquals("Bearer local-test-token", http.requests.get(1).headers.get("Authorization"));
        assertFalse(http.requests.get(1).body.contains("private_key"));
    }

    @Test
    void disabledOrMissingCredentials_failsClosedWithoutNetwork() {
        FakeHttp http = new FakeHttp();
        FcmPushClient client = new FcmPushClient(mapper, http, false,
                "missing-user.json", "missing-cons.json", "https://oauth.test/token",
                "https://fcm.test", Clock.systemUTC());

        assertFalse(client.isReady());
        assertFalse(client.send("user", Map.of("message", Map.of())));
        assertTrue(http.requests.isEmpty());
    }

    private Path serviceAccountFile() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] privateKey = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey)
                + "\n-----END PRIVATE KEY-----\n";
        Path file = tempDir.resolve("service-account.json");
        Files.writeString(file, mapper.writeValueAsString(Map.of(
                "client_email", "test@example.invalid",
                "private_key", pem,
                "project_id", "test-project",
                "token_uri", "https://oauth.test/token")));
        return file;
    }

    private static final class FakeHttp implements NotificationHttpTransport {
        final List<Response> responses = new ArrayList<>();
        final List<Request> requests = new ArrayList<>();

        @Override public Response exchange(String method, String url, Map<String, String> headers,
                                            String contentType, String body) {
            requests.add(new Request(method, url, headers, contentType, body));
            return responses.remove(0);
        }
    }

    private record Request(String method, String url, Map<String, String> headers,
                           String contentType, String body) {}
}
