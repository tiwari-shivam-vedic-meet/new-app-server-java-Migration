package com.vedicmeet.appserver.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.realtime.SocketEvents;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the third-party clients: they must POST to the exact Node endpoints with the
 * exact payload/headers, using config (never hardcoded secrets). A fake {@link HttpJsonClient}
 * captures the request so nothing leaves the JVM. Also pins a couple of Socket.IO event names.
 */
class IntegrationClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static class CapturingHttp implements HttpJsonClient {
        String url;
        Map<String, String> headers;
        String body;
        @Override public String post(String url, Map<String, String> headers, String jsonBody) {
            this.url = url; this.headers = headers; this.body = jsonBody; return "{\"ok\":true}";
        }
    }

    @Test
    void interakt_postsToMicroService_withEventPayload() throws Exception {
        CapturingHttp http = new CapturingHttp();
        InteraktClient client = new InteraktClient("http://micro.test/api", http, mapper);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("amount", 199);
        client.queueInteraktEvent("Registration Completed", "u1", "+919000000001", props, null);

        assertEquals("http://micro.test/api/segmentation/interakt-event", http.url);
        Map<?, ?> sent = mapper.readValue(http.body, Map.class);
        assertEquals("event", sent.get("eventType"));
        assertEquals("Registration Completed", sent.get("eventName"));
        assertEquals("u1", sent.get("userId"));
        assertEquals("+919000000001", sent.get("phone"));
    }

    @Test
    void interakt_refusesToClaimProviderWorkWithoutConfiguredTarget() {
        InteraktClient client = new InteraktClient("", new CapturingHttp(), mapper);
        assertFalse(client.isReady());
        assertThrows(IllegalStateException.class,
                () -> client.queueInteraktEvent("event", "u1", "9000000001", Map.of(), Map.of()));
    }

    @Test
    void pabbly_usesOnlyConfiguredWebhook_andJsonPayload() throws Exception {
        CapturingHttp http = new CapturingHttp();
        PabblyClient client = new PabblyClient(true, "https://pabbly.test/hook", http, mapper);

        client.send(Map.of("Name", "Test User", "Phone", "9000000001"));

        assertTrue(client.isReady());
        assertEquals("https://pabbly.test/hook", http.url);
        assertEquals("application/json", http.headers.get("Content-Type"));
        Map<?, ?> sent = mapper.readValue(http.body, Map.class);
        assertEquals("Test User", sent.get("Name"));
    }

    @Test
    void pabbly_isDisabledUnlessBothGateAndUrlArePresent() {
        assertFalse(new PabblyClient(false, "https://pabbly.test/hook",
                new CapturingHttp(), mapper).isReady());
        assertFalse(new PabblyClient(true, "", new CapturingHttp(), mapper).isReady());
    }

    @Test
    void cleverTap_postsToUpload_withAccountHeaders_andEventBody() throws Exception {
        CapturingHttp http = new CapturingHttp();
        CleverTapClient client = new CleverTapClient("ACCT-123", "PASS-xyz", http, mapper);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amount", 199);
        client.uploadEvent("9000000001", "wallet_recharge", data);

        assertEquals("https://api.clevertap.com/1/upload", http.url);
        assertEquals("ACCT-123", http.headers.get("X-CleverTap-Account-Id"));
        assertEquals("PASS-xyz", http.headers.get("X-CleverTap-Passcode"));
        Map<?, ?> sent = mapper.readValue(http.body, Map.class);
        java.util.List<?> d = (java.util.List<?>) sent.get("d");
        Map<?, ?> ev = (Map<?, ?>) d.get(0);
        assertEquals("event", ev.get("type"));
        assertEquals("wallet_recharge", ev.get("evtName"));
        assertEquals("9000000001", ev.get("identity"));
    }

    @Test
    void socketEventNames_matchNodeCatalog() {
        assertEquals("/app", SocketEvents.NAMESPACE_APP);
        assertEquals("accept_call", SocketEvents.ACCEPT_CALL);
        assertEquals("call_status_update", SocketEvents.CALL_STATUS_UPDATE);
        assertEquals("call_partially_accepted", SocketEvents.CALL_PARTIALLY_ACCEPTED);
        assertEquals("get-blocked-chats-ranges", SocketEvents.GET_BLOCKED_CHATS_RANGES);
    }
}
