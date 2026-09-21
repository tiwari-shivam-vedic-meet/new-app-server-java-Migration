package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payment-gateway controller — FAITHFUL port of the bf308ee transaction.js routes that were missing:
 * {@code GET /active-gateways} (authed read) and the {@code POST /phonepe-callback} /
 * {@code POST /paytm-callback} server-to-server callbacks (public, like Node). Served under {@code /v2}.
 *
 * <p>Public callbacks are treated only as notifications. PhonePe is independently checked through
 * its authenticated status API; Paytm requires both its checksum and an independent status check.
 * Only a provider-confirmed success reaches the shared idempotent wallet confirmation. Every money
 * route is additionally guarded by {@link MigrationWrite}, which remains off by default.</p>
 */
@RestController
@RequestMapping("/v2/v1/payment")
public class PaymentGatewayController {

    private final GatewayService service;
    private final PaymentInitiateService initiateService;
    private final PaymentWebhookService webhookService;
    private final RazorpaySignatureVerifier razorpaySignatureVerifier;
    private final GatewayCallbackVerificationService callbackVerification;
    private final ObjectMapper objectMapper;
    private final String razorpayWebhookSecret;

    public PaymentGatewayController(
            GatewayService service,
            PaymentInitiateService initiateService,
            PaymentWebhookService webhookService,
            RazorpaySignatureVerifier razorpaySignatureVerifier,
            GatewayCallbackVerificationService callbackVerification,
            ObjectMapper objectMapper,
            @Value("${vedicmeet.payment.razorpay-webhook-secret:}") String razorpayWebhookSecret) {
        this.service = service;
        this.initiateService = initiateService;
        this.webhookService = webhookService;
        this.razorpaySignatureVerifier = razorpaySignatureVerifier;
        this.callbackVerification = callbackVerification;
        this.objectMapper = objectMapper;
        this.razorpayWebhookSecret = razorpayWebhookSecret;
    }

    /** Node GET /active-gateways (authorization). */
    @GetMapping("/active-gateways")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> activeGateways() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activePaymentGateways", service.getActivePaymentGateways());
            return ApiResponse.ok("Active payment gateways fetched successfully", result);
        } catch (RuntimeException e) {
            return new ApiResponse<>(false, 500, e.getMessage(), new LinkedHashMap<>());
        }
    }

    /** Node POST /phonepe-callback (public): only acts on event 'checkout.order.completed'. */
    @PostMapping("/phonepe-callback")
    @MigrationWrite
    @SuppressWarnings("unchecked")
    public ApiResponse<?> phonePeCallback(@RequestBody(required = false) Map<String, Object> body) {
        try {
            GatewayCallbackVerificationService.Verification verification =
                    callbackVerification.verifyPhonePe(body);
            if (!verification.verified()) return callbackRejected(verification.message());
            return ApiResponse.ok("Payment callback received successfully",
                    service.phonePeCallback(verification.payload()));
        } catch (RuntimeException e) {
            return new ApiResponse<>(false, 500, e.getMessage(), new LinkedHashMap<>());
        }
    }

    /** Node POST /paytm-callback (public). */
    @PostMapping(value = "/paytm-callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    @MigrationWrite
    public ApiResponse<?> paytmCallback(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return processPaytmCallback(body);
        } catch (RuntimeException e) {
            return new ApiResponse<>(false, 500, e.getMessage(), new LinkedHashMap<>());
        }
    }

    /** Paytm commonly posts callbacks as HTML form fields rather than JSON. */
    @PostMapping(value = "/paytm-callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @MigrationWrite
    public ApiResponse<?> paytmFormCallback(@RequestParam MultiValueMap<String, String> form) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (form != null) form.forEach((key, values) ->
                body.put(key, values == null || values.isEmpty() ? null : values.get(0)));
        try {
            return processPaytmCallback(body);
        } catch (RuntimeException e) {
            return new ApiResponse<>(false, 500, e.getMessage(), new LinkedHashMap<>());
        }
    }

    /** Node POST /phonepe-initiate — DISABLED in production (always errors "Phonepe is not working…"). */
    @PostMapping("/phonepe-initiate")
    @MigrationWrite
    public ApiResponse<?> phonePeInitiate(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok("Payment initiated successfully", initiateService.phonePeInitiatePayment(body));
        } catch (RuntimeException e) {
            return new ApiResponse<>(false, 500, e.getMessage(), new LinkedHashMap<>());
        }
    }

    /** Node POST /paytm-initiate (authorization): validate mobile/email, build payload, create the order. */
    @PostMapping("/paytm-initiate")
    @RequireRole({Role.USER, Role.CONSULTANT})
    @MigrationWrite
    public ApiResponse<?> paytmInitiate(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok("Payment initiated successfully", initiateService.paytmInitiatePayment(body));
        } catch (RuntimeException e) {
            return new ApiResponse<>(false, 500, e.getMessage(), new LinkedHashMap<>());
        }
    }

    /**
     * Razorpay server webhook. Signature verification is over the raw request body, as required by
     * Razorpay, before JSON parsing or any database write. The response shape preserves Node's
     * HTTP-200 webhook contract.
     */
    @PostMapping(value = "/confirm/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @MigrationWrite
    public Map<String, Object> razorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-razorpay-signature", required = false) String signature,
            @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId) {
        try {
            if (razorpayWebhookSecret == null || razorpayWebhookSecret.isBlank()) {
                throw new IllegalStateException("Razorpay webhook verification is not configured");
            }
            if (!razorpaySignatureVerifier.isValid(rawBody, signature, razorpayWebhookSecret)) {
                throw new IllegalArgumentException("Invalid payment signature");
            }

            Map<String, Object> body = objectMapper.readValue(
                    rawBody, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (eventId != null && !eventId.isBlank()) {
                body.put("razorpayEventId", eventId);
            }
            webhookService.confirmPaymentWithWebhook(body);
            return Map.of("status", true);
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", 500);
            response.put("success", false);
            response.put("message", e.getMessage() == null ? "Internal error" : e.getMessage());
            response.put("result", new LinkedHashMap<>());
            return response;
        }
    }

    private ApiResponse<?> processPaytmCallback(Map<String, Object> body) {
        GatewayCallbackVerificationService.Verification verification =
                callbackVerification.verifyPaytm(body);
        if (!verification.verified()) return callbackRejected(verification.message());
        return ApiResponse.ok("Payment callback received successfully",
                service.paytmCallback(verification.payload()));
    }

    private ApiResponse<?> callbackRejected(String message) {
        return new ApiResponse<>(false, 500,
                message == null ? "Payment callback verification failed" : message,
                new LinkedHashMap<>());
    }
}
