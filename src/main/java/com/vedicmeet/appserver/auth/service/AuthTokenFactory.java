package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.security.JwtService;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Centralizes the additive Node-compatible JWT claim contract. */
@Service
public class AuthTokenFactory {

    private final JwtService jwt;
    private final long resetTtlSeconds;

    public AuthTokenFactory(JwtService jwt,
                            @Value("${vedicmeet.auth.password-reset-ttl-seconds:900}") long resetTtlSeconds) {
        this.jwt = jwt;
        this.resetTtlSeconds = resetTtlSeconds;
    }

    public String accountToken(String role, Document account) {
        Document details = account != null && account.get("details") instanceof Document d ? d : new Document();
        Map<String, Object> claims = new LinkedHashMap<>();
        put(claims, "email", first(details.get("email"), account == null ? null : account.get("email")));
        put(claims, "phone", details.get("phone"));
        put(claims, "phonePrefix", details.get("phonePrefix"));
        put(claims, "_id", id(account));
        claims.put("role", role);
        claims.put("type", Role.CONSULTANT.equals(role) ? "cons" : role);
        claims.put("tokenVersion", intValue(account == null ? null : account.get("authTokenVersion")));
        return jwt.sign(claims);
    }

    public String passwordResetToken(Document admin, String nonce) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("email", String.valueOf(admin.get("email")));
        claims.put("_id", id(admin));
        claims.put("role", String.valueOf(admin.get("role")));
        claims.put("purpose", "password_reset");
        claims.put("nonce", nonce);
        return jwt.sign(claims, resetTtlSeconds);
    }

    private String id(Document document) {
        if (document == null || document.get("_id") == null) return null;
        Object value = document.get("_id");
        return value instanceof ObjectId oid ? oid.toHexString() : String.valueOf(value);
    }

    private Object first(Object first, Object second) { return first != null ? first : second; }
    private void put(Map<String, Object> claims, String key, Object value) { if (value != null) claims.put(key, value); }
    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
