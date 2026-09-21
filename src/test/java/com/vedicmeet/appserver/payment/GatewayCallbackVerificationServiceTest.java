package com.vedicmeet.appserver.payment;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayCallbackVerificationServiceTest {

    @Test
    void phonePeRequiresSupportedEventOrderAndIndependentSuccess() {
        PhonePeGatewayClient phonePe = mock(PhonePeGatewayClient.class);
        PaytmGatewayClient paytm = mock(PaytmGatewayClient.class);
        GatewayCallbackVerificationService service = new GatewayCallbackVerificationService(phonePe, paytm);

        assertFalse(service.verifyPhonePe(Map.of("event", "other")).verified());
        verify(phonePe, never()).status("ORDER-1");

        Map<String, Object> callback = Map.of(
                "event", "checkout.order.completed",
                "payload", Map.of("merchantOrderId", "ORDER-1", "state", "COMPLETED"));
        when(phonePe.status("ORDER-1")).thenReturn(PaymentReconciliationService.ReconStatus.PENDING);
        assertFalse(service.verifyPhonePe(callback).verified());
        when(phonePe.status("ORDER-1")).thenReturn(PaymentReconciliationService.ReconStatus.SUCCESS);
        assertTrue(service.verifyPhonePe(callback).verified());
    }

    @Test
    void paytmRequiresChecksumAndIndependentSuccess() {
        PhonePeGatewayClient phonePe = mock(PhonePeGatewayClient.class);
        PaytmGatewayClient paytm = mock(PaytmGatewayClient.class);
        GatewayCallbackVerificationService service = new GatewayCallbackVerificationService(phonePe, paytm);
        Map<String, Object> callback = Map.of("ORDERID", "ORDER-1", "STATUS", "TXN_SUCCESS");

        when(paytm.verifyCallback(callback)).thenReturn(false);
        assertFalse(service.verifyPaytm(callback).verified());
        verify(paytm, never()).status("ORDER-1");

        when(paytm.verifyCallback(callback)).thenReturn(true);
        when(paytm.status("ORDER-1")).thenReturn(PaymentReconciliationService.ReconStatus.ERROR);
        assertFalse(service.verifyPaytm(callback).verified());
        when(paytm.status("ORDER-1")).thenReturn(PaymentReconciliationService.ReconStatus.SUCCESS);
        assertTrue(service.verifyPaytm(callback).verified());
    }
}
