package com.vedicmeet.appserver.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link RazorpaySignatureVerifier} matches Node's
 * {@code crypto.createHmac('sha256', secret).update(payload).digest('hex')} exactly, and rejects a
 * tampered signature. The independent expected value is computed here with the JDK Mac directly.
 */
class RazorpaySignatureVerifierTest {

    private final RazorpaySignatureVerifier verifier = new RazorpaySignatureVerifier();
    private static final String SECRET = "whsec_test_123";
    private static final String PAYLOAD = "{\"event\":\"payment.captured\",\"id\":\"pay_W1\"}";

    @Test
    void validSignatureAccepted() {
        String expected = independentHmac(SECRET, PAYLOAD);
        assertTrue(verifier.isValid(PAYLOAD, expected, SECRET));
        assertEquals(expected, verifier.hmacSha256Hex(SECRET, PAYLOAD));
    }

    @Test
    void tamperedSignatureRejected() {
        assertFalse(verifier.isValid(PAYLOAD, "deadbeef".repeat(8), SECRET));
    }

    @Test
    void nullSignatureRejected() {
        assertFalse(verifier.isValid(PAYLOAD, null, SECRET));
    }

    private static String independentHmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
