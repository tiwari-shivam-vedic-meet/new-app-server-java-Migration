package com.vedicmeet.appserver.payment;

import java.util.Map;

/**
 * Outcome of {@link PaymentWebhookService#confirmPaymentWithWebhook}. Mirrors the Node return
 * shapes: a credited capture carries the invoice email + payload; a duplicate/already-processed or
 * a failed/non-captured event carries neither (so the caller sends no email).
 */
public final class WebhookResult {

    public enum Kind { CREDITED, ALREADY_PROCESSED, FAILED }

    private final Kind kind;
    private final String email;
    private final Map<String, Object> invoicePayload;

    private WebhookResult(Kind kind, String email, Map<String, Object> invoicePayload) {
        this.kind = kind;
        this.email = email;
        this.invoicePayload = invoicePayload;
    }

    public static WebhookResult credited(String email, Map<String, Object> invoicePayload) {
        return new WebhookResult(Kind.CREDITED, email, invoicePayload);
    }

    public static WebhookResult alreadyProcessed() {
        return new WebhookResult(Kind.ALREADY_PROCESSED, null, null);
    }

    public static WebhookResult failed() {
        return new WebhookResult(Kind.FAILED, null, null);
    }

    public Kind kind() { return kind; }

    /** Non-null only when a capture actually credited the wallet (Node: response.email). */
    public String email() { return email; }

    public Map<String, Object> invoicePayload() { return invoicePayload; }
}
