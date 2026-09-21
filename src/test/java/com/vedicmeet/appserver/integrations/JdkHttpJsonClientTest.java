package com.vedicmeet.appserver.integrations;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdkHttpJsonClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void returnsBodyOnlyForTwoHundredResponse() throws Exception {
        start(200, "{\"ok\":true}");
        String body = new JdkHttpJsonClient().post(url(), Map.of("X-Test", "1"), "{}");
        assertEquals("{\"ok\":true}", body);
    }

    @Test
    void nonTwoHundredResponseIsFailureNotSuccess() throws Exception {
        start(500, "{\"error\":true}");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> new JdkHttpJsonClient().post(url(), Map.of(), "{}"));
        assertEquals("HTTP_POST_FAILED", error.getMessage());
    }

    private void start(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/test";
    }
}
