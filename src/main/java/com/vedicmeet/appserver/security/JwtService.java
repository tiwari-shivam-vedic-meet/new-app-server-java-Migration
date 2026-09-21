package com.vedicmeet.appserver.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Compatible with the Node service's jsonwebtoken usage:
 *   jwt.sign(payload, JWT_SECRET, { expiresIn: '30d' })   // default alg HS256
 *   jwt.verify(token, JWT_SECRET)
 *
 * Implemented directly on HMAC-SHA256 (not jjwt) for two reasons:
 *   1. jsonwebtoken uses the secret as raw UTF-8 bytes and does NOT enforce a
 *      256-bit minimum key length. jjwt rejects short keys, which would make a
 *      Node-signed token that VedicMeet already issues un-verifiable here.
 *   2. Verification recomputes the MAC over the received "header.payload" bytes
 *      as-is, so any token Node signs verifies here regardless of claim order.
 *
 * Token shape produced/consumed:
 *   header : {"alg":"HS256","typ":"JWT"}
 *   payload: caller claims + iat + exp (unix seconds)
 */
@Service
public class JwtService {

    private static final long DEFAULT_TTL_SECONDS = 30L * 24 * 60 * 60; // '30d'
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] secret;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder b64url = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64urlDec = Base64.getUrlDecoder();

    public JwtService(@Value("${vedicmeet.jwt.secret}") String jwtSecret) {
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(Map<String, Object> claims) {
        return sign(claims, DEFAULT_TTL_SECONDS);
    }

    public String sign(Map<String, Object> claims, long ttlSeconds) {
        try {
            long now = Instant.now().getEpochSecond();
            ObjectNode payload = mapper.createObjectNode();
            for (Map.Entry<String, Object> e : claims.entrySet()) {
                payload.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
            if (!payload.hasNonNull("jti")) {
                payload.put("jti", UUID.randomUUID().toString());
            }
            payload.put("iat", now);
            payload.put("exp", now + ttlSeconds);

            String headerSeg = b64url.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            String payloadSeg = b64url.encodeToString(mapper.writeValueAsBytes(payload));
            String signingInput = headerSeg + "." + payloadSeg;
            String sig = b64url.encodeToString(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create token", e);
        }
    }

    /** Verifies signature + expiry and returns the claims. Throws on any failure (mirrors Node). */
    public Map<String, Object> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed token");
            }
            JsonNode header = mapper.readTree(b64urlDec.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                throw new SecurityException("Unsupported token algorithm");
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actual = b64urlDec.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new SecurityException("Invalid signature");
            }
            JsonNode payload = mapper.readTree(b64urlDec.decode(parts[1]));
            if (payload.has("exp") && payload.get("exp").asLong() <= Instant.now().getEpochSecond()) {
                throw new SecurityException("Token expired");
            }
            Map<String, Object> claims = new LinkedHashMap<>();
            payload.fields().forEachRemaining(f -> claims.put(f.getKey(), toValue(f.getValue())));
            return claims;
        } catch (Exception e) {
            throw new RuntimeException("Invalid token", e);
        }
    }

    private Object toValue(JsonNode n) {
        if (n.isTextual()) return n.asText();
        if (n.isInt() || n.isLong()) return n.asLong();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isDouble()) return n.asDouble();
        return n.toString();
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
