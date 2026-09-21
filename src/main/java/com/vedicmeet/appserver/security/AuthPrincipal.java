package com.vedicmeet.appserver.security;

import java.util.Map;

/**
 * The authenticated caller derived from a verified JWT. Mirrors what the Node
 * middleware attaches to req (phone + phonePrefix + role identify the account;
 * the full user/consultant document is loaded separately, as AuthCache does).
 *
 * Set as a request attribute ("authPrincipal") by JwtAuthFilter.
 */
public class AuthPrincipal {
    private final String phone;
    private final String phonePrefix;
    private final String role;
    private final String email;
    private final String id;
    private final String jti;
    private final Map<String, Object> claims;

    public AuthPrincipal(Map<String, Object> claims) {
        this.claims = claims;
        this.phone = claims.get("phone") == null ? null : String.valueOf(claims.get("phone"));
        this.phonePrefix = claims.get("phonePrefix") == null ? null : String.valueOf(claims.get("phonePrefix"));
        Object roleClaim = claims.get("role") != null ? claims.get("role") : claims.get("type");
        String normalizedRole = roleClaim == null ? null : String.valueOf(roleClaim);
        this.role = "cons".equals(normalizedRole) ? Role.CONSULTANT : normalizedRole;
        this.email = claims.get("email") == null ? null : String.valueOf(claims.get("email"));
        this.id = claims.get("_id") == null ? null : String.valueOf(claims.get("_id"));
        this.jti = claims.get("jti") == null ? null : String.valueOf(claims.get("jti"));
    }

    public String getPhone() { return phone; }
    public String getPhonePrefix() { return phonePrefix; }
    public String getRole() { return role; }
    public String getEmail() { return email; }
    public String getId() { return id; }
    public String getJti() { return jti; }
    public Map<String, Object> getClaims() { return claims; }

    public Integer getTokenVersion() {
        Object value = claims == null ? null : claims.get("tokenVersion");
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
}
