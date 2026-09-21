package com.vedicmeet.appserver.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatServerClientTest {

    private HttpServer server;
    private String lastPath;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void blankConfigurationFailsClosedWithoutNetworkCall() {
        ChatServerClient client = new ChatServerClient("", new ObjectMapper());
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.createThreadSupport(Map.of("userId", "u1")));
        assertEquals("CHAT_SERVER_CALL_FAILED", error.getMessage());
        assertTrue(error.getCause().getMessage().contains("NOT_CONFIGURED"));
    }

    @Test
    void downstreamHttpFailureIsNotParsedAsSuccess() throws Exception {
        start(503, "{\"success\":false}");
        ChatServerClient client = new ChatServerClient(baseUrl(), new ObjectMapper());
        assertThrows(RuntimeException.class,
                () -> client.createThreadSupport(Map.of("userId", "u1")));
    }

    @Test
    void successfulResponseIsParsed() throws Exception {
        start(200, "{\"success\":true,\"data\":{\"threadId\":\"t1\"}}");
        ChatServerClient client = new ChatServerClient(baseUrl(), new ObjectMapper());
        assertEquals(true, client.createThreadSupport(Map.of("userId", "u1")).get("success"));
    }

    @Test
    void consultationMethodsUseTheExactNodeChatServerPaths() throws Exception {
        start(200, "{\"success\":true,\"data\":\"thread-1\"}");
        ChatServerClient client = new ChatServerClient(baseUrl(), new ObjectMapper());

        client.createThread(Map.of("user", Map.of("_id", "u1")));
        assertEquals("/api/chats/create-thread", lastPath);
        client.feedFirstMessageForm(Map.of("threadId", "thread-1"));
        assertEquals("/api/chats/feed-first-message-form", lastPath);
        client.createSummary(Map.of("sessionId", "w1"));
        assertEquals("/api/chats/create-summary", lastPath);
        assertTrue(client.isReady());
    }

    private void start(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }
}
