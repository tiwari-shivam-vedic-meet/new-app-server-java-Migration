package com.vedicmeet.appserver.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Thin client for the Vedic Meet chat-server, ported from the two Node axios calls in
 * utils/classes/support.js:
 *   - POST {CHAT_SERVER_URL}/chats/create-thread-support  (initiate_query)
 *   - POST {CHAT_SERVER_URL}/support/create-query         (chat_send, admin first-reply)
 *
 * Uses the JDK {@link HttpClient} (no extra dependency) with normal certificate and hostname
 * verification. The Node service currently disables TLS verification on this integration; the
 * migration must not copy that security debt. Install a valid certificate on chat-server instead.
 */
@Component
public class ChatServerClient {

    private static final Logger log = LoggerFactory.getLogger(ChatServerClient.class);

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public ChatServerClient(@Value("${vedicmeet.chat.server-url:}") String baseUrl,
                            ObjectMapper mapper) {
        this(baseUrl, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    ChatServerClient(String baseUrl, ObjectMapper mapper, HttpClient http) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.mapper = mapper;
        this.http = http;
    }

    /** POST /chats/create-thread-support — returns the parsed { success, data } body. */
    public Map<String, Object> createThreadSupport(Map<String, Object> payload) {
        return post("/chats/create-thread-support", payload);
    }

    /** POST /support/create-query — returns the parsed { success, data } body. */
    public Map<String, Object> createQuery(Map<String, Object> payload) {
        return post("/support/create-query", payload);
    }

    /** Node business-logics.js: create the user/consultant consultation thread. */
    public Map<String, Object> createThread(Map<String, Object> payload) {
        return post("/chats/create-thread", payload);
    }

    /** Node consultant/support.js: create or refresh the post-consultation leave-message thread. */
    public Map<String, Object> createThreadLeaveMessage(Map<String, Object> payload) {
        return post("/chats/create-thread-leave-message", payload);
    }

    /** Node consultant/session.js init: consultant-to-admin support thread. */
    public Map<String, Object> createThreadAdmin(Map<String, Object> payload) {
        return post("/chats/create-thread-admin", payload);
    }

    /** Node fixed-session accept: copy all booked forms into the consultation thread. */
    public Map<String, Object> feedFirstMessageFormForSessions(Map<String, Object> payload) {
        return post("/chats/feed-first-message-form-for-sessions", payload);
    }

    /** Node quick-query claim: create the assigned user/consultant thread. */
    public Map<String, Object> createThreadForQuery(Map<String, Object> payload) {
        return post("/chats/create-thread-for-query", payload);
    }

    /** Node quick-query claim: send the consultant's first greeting through chat-server. */
    public Map<String, Object> sendMessageUsingRestApi(Map<String, Object> payload) {
        return post("/chats/send-message-using-rest-api", payload);
    }

    /** Node business-logics.js: copy the booking form into a newly-created chat thread. */
    public Map<String, Object> feedFirstMessageForm(Map<String, Object> payload) {
        return post("/chats/feed-first-message-form", payload);
    }

    /** Node call.js handleCallTimeout: generate the post-consultation chat summary. */
    public Map<String, Object> createSummary(Map<String, Object> payload) {
        return post("/chats/create-summary", payload);
    }

    /** Node user/session.js consultant-follow-up-messages read. */
    public Map<String, Object> consultantFollowUpsForUser(String userId) {
        String query = "?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return get("/chats/consultant-follow-ups-for-user" + query);
    }

    /** Admin support history: GET /chats/get-thread-messages. */
    public Map<String, Object> threadMessages(String threadId, int page, int limit) {
        String query = "?threadId=" + URLEncoder.encode(threadId, StandardCharsets.UTF_8)
                + "&page=" + page + "&limit=" + limit;
        return get("/chats/get-thread-messages" + query);
    }

    public boolean isReady() {
        return !baseUrl.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> payload) {
        try {
            if (baseUrl.isBlank()) {
                throw new IllegalStateException("CHAT_SERVER_URL_NOT_CONFIGURED");
            }
            String body = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("CHAT_SERVER_HTTP_" + response.statusCode());
            }
            return mapper.readValue(response.body(), Map.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("chat-server POST {} interrupted", path);
            throw new RuntimeException("CHAT_SERVER_CALL_INTERRUPTED", e);
        } catch (Exception e) {
            log.warn("chat-server POST {} failed: {}", path, e.getMessage());
            throw new RuntimeException("CHAT_SERVER_CALL_FAILED", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        try {
            if (baseUrl.isBlank()) throw new IllegalStateException("CHAT_SERVER_URL_NOT_CONFIGURED");
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("CHAT_SERVER_HTTP_" + response.statusCode());
            }
            return mapper.readValue(response.body(), Map.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("CHAT_SERVER_CALL_INTERRUPTED", e);
        } catch (Exception e) {
            log.warn("chat-server GET {} failed: {}", path, e.getMessage());
            throw new RuntimeException("CHAT_SERVER_CALL_FAILED", e);
        }
    }

}
