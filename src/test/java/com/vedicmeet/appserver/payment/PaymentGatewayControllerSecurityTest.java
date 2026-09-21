package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentGatewayControllerSecurityTest {

    private static final String SECRET = "test-webhook-secret";

    private GatewayService gatewayService;
    private PaymentWebhookService webhookService;
    private RazorpaySignatureVerifier verifier;
    private GatewayCallbackVerificationService callbackVerification;
    private PaymentGatewayController controller;

    @BeforeEach
    void setUp() {
        gatewayService = mock(GatewayService.class);
        webhookService = mock(PaymentWebhookService.class);
        verifier = new RazorpaySignatureVerifier();
        callbackVerification = mock(GatewayCallbackVerificationService.class);
        when(callbackVerification.verifyPhonePe(any())).thenReturn(
                GatewayCallbackVerificationService.Verification.rejected("not verified"));
        when(callbackVerification.verifyPaytm(any())).thenReturn(
                GatewayCallbackVerificationService.Verification.rejected("not verified"));
        controller = new PaymentGatewayController(
                gatewayService,
                mock(PaymentInitiateService.class),
                webhookService,
                verifier,
                callbackVerification,
                new ObjectMapper(),
                SECRET);
    }

    @Test
    void razorpayWebhook_validRawBodySignature_processesOnce() {
        String raw = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"status\":\"captured\"}}}}";
        String signature = verifier.hmacSha256Hex(SECRET, raw);

        Map<String, Object> response = controller.razorpayWebhook(raw, signature, "event_1");

        assertEquals(true, response.get("status"));
        verify(webhookService).confirmPaymentWithWebhook(any(LinkedHashMap.class));
    }

    @Test
    void razorpayWebhook_tamperedBody_isRejectedBeforeMoneyService() {
        String signed = "{\"event\":\"payment.captured\"}";
        String tampered = "{\"event\":\"payment.failed\"}";

        Map<String, Object> response = controller.razorpayWebhook(
                tampered, verifier.hmacSha256Hex(SECRET, signed), "event_2");

        assertEquals(false, response.get("success"));
        assertEquals(500, response.get("code"));
        verify(webhookService, never()).confirmPaymentWithWebhook(any());
    }

    @Test
    void phonePeAndPaytmCallbacks_failClosedUntilProviderVerificationSucceeds() {
        assertFalse(controller.phonePeCallback(Map.of()).isSuccess());
        assertFalse(controller.paytmCallback(Map.of()).isSuccess());
        verify(gatewayService, never()).phonePeCallback(any());
        verify(gatewayService, never()).paytmCallback(any());
    }

    @Test
    void verifiedCallbacks_delegateOnlyTheVerifiedPayload() {
        Map<String, Object> phonePayload = Map.of("state", "COMPLETED", "merchantOrderId", "P1");
        Map<String, Object> paytmPayload = Map.of("STATUS", "TXN_SUCCESS", "ORDERID", "T1");
        when(callbackVerification.verifyPhonePe(any())).thenReturn(
                GatewayCallbackVerificationService.Verification.accepted(phonePayload));
        when(callbackVerification.verifyPaytm(any())).thenReturn(
                GatewayCallbackVerificationService.Verification.accepted(paytmPayload));

        controller.phonePeCallback(Map.of("event", "checkout.order.completed", "payload", phonePayload));
        controller.paytmCallback(paytmPayload);

        verify(gatewayService).phonePeCallback(phonePayload);
        verify(gatewayService).paytmCallback(paytmPayload);
    }
}
