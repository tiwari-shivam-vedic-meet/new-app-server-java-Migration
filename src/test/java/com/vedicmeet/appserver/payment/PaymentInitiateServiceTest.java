package com.vedicmeet.appserver.payment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful initiate flows of {@link PaymentInitiateService} (bf308ee phonePe/paytm initiate). */
class PaymentInitiateServiceTest {

    @Test
    void phonePeInitiate_isDisabled_alwaysThrows() {
        var svc = new PaymentInitiateService(new Fake());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.phonePeInitiatePayment(Map.of("amount", 100)));
        assertEquals("Phonepe is not working.Please Try Some another Payment Method", ex.getMessage());
    }

    @Test
    void paytmInitiate_noMobileNoEmail_throwsValidation() {
        var svc = new PaymentInitiateService(new Fake());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.paytmInitiatePayment(Map.of("merchantOrderId", "ORD1")));
        assertTrue(ex.getMessage().contains("valid mobile number"));
    }

    @Test
    void paytmInitiate_validMobile_fillsEmailFallback_andForwardsPayload() {
        Fake s = new Fake();
        s.response = ok();
        var svc = new PaymentInitiateService(s);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("merchantOrderId", "ORD2");
        in.put("mobileNumber", "9000000001");
        Map<String, Object> r = svc.paytmInitiatePayment(in);
        assertEquals(true, r.get("success"));
        assertEquals("ORD2", s.payload.get("orderId"));
        assertEquals("9000000001", s.payload.get("mobileNumber"));
        assertEquals("user9000000001@vedicmeet.com", s.payload.get("email"), "email fallback from mobile");
        assertTrue(String.valueOf(s.payload.get("customerId")).startsWith("CUST_"));
    }

    @Test
    void paytmInitiate_gatewayFailure_throws() {
        Fake s = new Fake();
        s.response = new LinkedHashMap<>();
        s.response.put("success", false);
        var svc = new PaymentInitiateService(s);
        assertThrows(IllegalStateException.class,
                () -> svc.paytmInitiatePayment(Map.of("email", "a@b.com", "merchantOrderId", "O")));
    }

    private Map<String, Object> ok() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("data", Map.of("token", "T"));
        return r;
    }

    static class Fake implements PaymentInitiateStore {
        Map<String, Object> response;
        Map<String, Object> payload;

        @Override public Map<String, Object> initiatePaytmTransaction(Map<String, Object> paymentData) {
            payload = paymentData;
            return response;
        }
    }
}
