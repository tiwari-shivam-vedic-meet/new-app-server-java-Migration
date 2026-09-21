package com.vedicmeet.appserver.payment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful {@link GatewayService} (bf308ee active-gateways + phonePe/paytm callbacks). */
class GatewayServiceTest {

    @Test
    void activeGateways_nullSetting_defaultsToAllThree() {
        Fake s = new Fake();
        s.raw = null;
        assertEquals(List.of("paytm", "phonepe", "razorpay"), new GatewayService(s).getActivePaymentGateways());
    }

    @Test
    void activeGateways_lowercasesAndFiltersInvalid() {
        Fake s = new Fake();
        s.raw = List.of("PhonePe", "PAYTM", "not-a-gateway");
        assertEquals(List.of("phonepe", "paytm"), new GatewayService(s).getActivePaymentGateways());
    }

    @Test
    void activeGateways_allInvalid_fallsBackToDefault() {
        Fake s = new Fake();
        s.raw = List.of("bitcoin");
        assertEquals(List.of("paytm", "phonepe", "razorpay"), new GatewayService(s).getActivePaymentGateways());
    }

    @Test
    void phonePeCallback_completed_confirmsWithOrderAndTransactionId() {
        Fake s = new Fake();
        s.confirm = new GatewayStore.ConfirmResult(true, "ok");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", "COMPLETED");
        payload.put("merchantOrderId", "ORD1");
        payload.put("paymentDetails", List.of(Map.of("transactionId", "PPE_TXN1")));

        Map<String, Object> r = new GatewayService(s).phonePeCallback(payload);
        assertEquals(true, r.get("success"));
        assertEquals("ORD1", s.confirmOrderId);
        assertEquals("PPE_TXN1", s.confirmPaymentId);
    }

    @Test
    void phonePeCallback_notCompleted_returnsFailure_noConfirm() {
        Fake s = new Fake();
        Map<String, Object> r = new GatewayService(s).phonePeCallback(Map.of("state", "PENDING"));
        assertEquals(false, r.get("success"));
        assertEquals("Payment confirmation failed!", r.get("message"));
        assertNull(s.confirmOrderId);
    }

    @Test
    void paytmCallback_missingOrderId_returnsRequiredError() {
        Fake s = new Fake();
        Map<String, Object> r = new GatewayService(s).paytmCallback(Map.of("STATUS", "TXN_SUCCESS"));
        assertEquals(false, r.get("success"));
        assertEquals("Order ID is required for payment verification", r.get("message"));
    }

    @Test
    void paytmCallback_success_confirmsWithTxnId() {
        Fake s = new Fake();
        s.confirm = new GatewayStore.ConfirmResult(true, null);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("ORDERID", "ORD9");
        in.put("STATUS", "TXN_SUCCESS");
        in.put("TXNID", "PTM_TXN9");
        Map<String, Object> r = new GatewayService(s).paytmCallback(in);
        assertEquals(true, r.get("success"));
        assertEquals("Payment confirmed successfully", r.get("message"));
        assertEquals("ORD9", s.confirmOrderId);
        assertEquals("PTM_TXN9", s.confirmPaymentId);
    }

    @Test
    void paytmCallback_respCode01_alsoConfirms() {
        Fake s = new Fake();
        s.confirm = new GatewayStore.ConfirmResult(true, "done");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("orderId", "ORD10");
        in.put("RESPCODE", "01");
        in.put("TXNID", "T10");
        assertEquals(true, new GatewayService(s).paytmCallback(in).get("success"));
        assertEquals("ORD10", s.confirmOrderId);
    }

    @Test
    void paytmCallback_pending_returnsPendingStatus_noConfirm() {
        Fake s = new Fake();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("ORDERID", "ORD11");
        in.put("STATUS", "PENDING");
        Map<String, Object> r = new GatewayService(s).paytmCallback(in);
        assertEquals(false, r.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("PENDING", data.get("status"));
        assertNull(s.confirmOrderId);
    }

    static class Fake implements GatewayStore {
        List<String> raw;
        GatewayStore.ConfirmResult confirm = new ConfirmResult(false, "not wired");
        String confirmOrderId, confirmPaymentId;

        @Override public List<String> readActivePaymentGatewaysRaw() { return raw; }
        @Override public ConfirmResult confirmPayment(String orderId, String paymentId) {
            confirmOrderId = orderId;
            confirmPaymentId = paymentId;
            return confirm;
        }
    }
}
