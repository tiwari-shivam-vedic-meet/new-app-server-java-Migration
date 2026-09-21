package com.vedicmeet.appserver.payment;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Razorpay webhook signature check. The Java endpoint intentionally improves the Node implementation
 * by signing the untouched raw request body, which is Razorpay's documented contract, before parsing.
 */
@Component
public class RazorpaySignatureVerifier {

    /** Constant-time compare of HMAC-SHA256(secret, signedPayload) hex vs the incoming signature. */
    public boolean isValid(String signedPayload, String incomingSignatureHex, String secret) {
        if (incomingSignatureHex == null) return false;
        String generated = hmacSha256Hex(secret, signedPayload);
        return constantTimeEquals(generated, incomingSignatureHex);
    }

    public String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
