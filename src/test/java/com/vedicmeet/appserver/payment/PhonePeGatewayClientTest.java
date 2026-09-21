package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhonePeGatewayClientTest {

    @Test
    void disabledConfigurationFailsClosedWithoutNetwork() {
        FakeHttp http = new FakeHttp();
        PhonePeGatewayClient client = client(http, false);

        assertEquals(PaymentReconciliationService.ReconStatus.ERROR, client.status("ORDER-1"));
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void providerCompletedStatusUsesOauthAndReturnsSuccess() {
        FakeHttp http = new FakeHttp();
        http.responses.add(new PaymentHttpTransport.Response(200, "{\"access_token\":\"token\"}"));
        http.responses.add(new PaymentHttpTransport.Response(200, "{\"state\":\"COMPLETED\"}"));

        assertEquals(PaymentReconciliationService.ReconStatus.SUCCESS, client(http, true).status("ORDER 1"));
        assertEquals("POST", http.requests.get(0).method);
        assertEquals("application/x-www-form-urlencoded", http.requests.get(0).contentType);
        assertTrue(http.requests.get(0).body.contains("grant_type=client_credentials"));
        assertEquals("GET", http.requests.get(1).method);
        assertTrue(http.requests.get(1).url.endsWith("/ORDER%201/status"));
        assertEquals("O-Bearer token", http.requests.get(1).headers.get("Authorization"));
    }

    @Test
    void providerPendingAndServerErrorRemainNonSuccess() {
        FakeHttp pending = new FakeHttp();
        pending.responses.add(new PaymentHttpTransport.Response(200, "{\"access_token\":\"token\"}"));
        pending.responses.add(new PaymentHttpTransport.Response(200, "{\"state\":\"PENDING\"}"));
        assertEquals(PaymentReconciliationService.ReconStatus.PENDING, client(pending, true).status("O"));

        FakeHttp serverError = new FakeHttp();
        serverError.responses.add(new PaymentHttpTransport.Response(200, "{\"access_token\":\"token\"}"));
        serverError.responses.add(new PaymentHttpTransport.Response(503, "{}"));
        assertEquals(PaymentReconciliationService.ReconStatus.ERROR, client(serverError, true).status("O"));
    }

    private PhonePeGatewayClient client(FakeHttp http, boolean enabled) {
        return new PhonePeGatewayClient(http, new ObjectMapper(), enabled,
                "test-client", "test-secret", "https://phonepe.test/oauth",
                "https://phonepe.test/order");
    }

    static final class FakeHttp implements PaymentHttpTransport {
        final java.util.List<Request> requests = new ArrayList<>();
        final java.util.List<Response> responses = new ArrayList<>();

        @Override
        public Response exchange(String method, String url, Map<String, String> headers,
                                 String contentType, String body) {
            requests.add(new Request(method, url, headers, contentType, body));
            return responses.remove(0);
        }
    }

    record Request(String method, String url, Map<String, String> headers, String contentType, String body) {}
}
