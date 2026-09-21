package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.pg.merchant.PaytmChecksum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaytmGatewayClientTest {

    private static final String TEST_KEY = "testmerchantkey1";

    @Test
    void disabledConfigurationFailsClosedWithoutNetwork() {
        FakeHttp http = new FakeHttp();
        PaytmGatewayClient client = client(http, false);

        assertEquals(PaymentReconciliationService.ReconStatus.ERROR, client.status("ORDER-1"));
        assertFalse(client.verifyCallback(Map.of("ORDERID", "ORDER-1")));
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void initiateSignsExactBodyAndReturnsTransactionToken() {
        FakeHttp http = new FakeHttp();
        http.responses.add(new PaymentHttpTransport.Response(200,
                "{\"body\":{\"resultInfo\":{\"resultStatus\":\"S\"},\"txnToken\":\"TOKEN-1\"}}"));

        Map<String, Object> result = client(http, true).initiate(Map.of(
                "orderId", "ORDER-1", "customerId", "CUSTOMER-1", "amount", 100,
                "mobileNumber", "9000000001"));

        assertEquals(true, result.get("success"));
        assertTrue(http.requests.get(0).url.contains("orderId=ORDER-1"));
        assertTrue(http.requests.get(0).body.contains("\"signature\""));
        assertTrue(http.requests.get(0).body.contains("\"value\":\"100.00\""));
    }

    @Test
    void statusMapsSuccessPendingAndProviderError() {
        FakeHttp success = new FakeHttp();
        success.responses.add(new PaymentHttpTransport.Response(200,
                "{\"body\":{\"resultInfo\":{\"resultStatus\":\"TXN_SUCCESS\",\"resultCode\":\"01\"}}}"));
        assertEquals(PaymentReconciliationService.ReconStatus.SUCCESS, client(success, true).status("O"));

        FakeHttp pending = new FakeHttp();
        pending.responses.add(new PaymentHttpTransport.Response(200,
                "{\"body\":{\"resultInfo\":{\"resultStatus\":\"PENDING\",\"resultCode\":\"402\"}}}"));
        assertEquals(PaymentReconciliationService.ReconStatus.PENDING, client(pending, true).status("O"));

        FakeHttp failure = new FakeHttp();
        failure.responses.add(new PaymentHttpTransport.Response(500, "{}"));
        assertEquals(PaymentReconciliationService.ReconStatus.ERROR, client(failure, true).status("O"));
    }

    @Test
    void callbackChecksumIsVerifiedByOfficialLibrary() throws Exception {
        TreeMap<String, String> fields = new TreeMap<>();
        fields.put("ORDERID", "ORDER-1");
        fields.put("STATUS", "TXN_SUCCESS");
        String checksum = PaytmChecksum.generateSignature(fields, TEST_KEY);
        Map<String, Object> callback = new LinkedHashMap<>(fields);
        callback.put("CHECKSUMHASH", checksum);

        PaytmGatewayClient client = client(new FakeHttp(), true);
        assertTrue(client.verifyCallback(callback));
        callback.put("STATUS", "TXN_FAILURE");
        assertFalse(client.verifyCallback(callback));
    }

    private PaytmGatewayClient client(FakeHttp http, boolean enabled) {
        return new PaytmGatewayClient(http, new ObjectMapper(), enabled,
                "TEST_MID", TEST_KEY, "https://paytm.test", "https://callback.test/paytm", "WEBSTAGING");
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
