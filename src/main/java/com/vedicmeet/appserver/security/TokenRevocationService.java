package com.vedicmeet.appserver.security;

import com.vedicmeet.appserver.cache.CacheService;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Adds logout-token invalidation without breaking Node-issued tokens. Old tokens have no {@code jti}
 * and continue to use the account-status/version checks; Java-issued tokens have a {@code jti} and
 * can be revoked until their natural expiry.
 */
@Service
public class TokenRevocationService {

    private static final String PREFIX = "auth:revoked:";
    private final CacheService cache;

    public TokenRevocationService(CacheService cache) {
        this.cache = cache;
    }

    public boolean isRevoked(AuthPrincipal principal) {
        return principal != null && principal.getJti() != null
                && Boolean.TRUE.equals(cache.get(PREFIX + principal.getJti(), Boolean.class));
    }

    public void revoke(AuthPrincipal principal) {
        if (principal == null || principal.getJti() == null) return;
        long expiry = longClaim(principal, "exp");
        long ttl = Math.max(1, expiry - Instant.now().getEpochSecond());
        cache.set(PREFIX + principal.getJti(), true, ttl);
    }

    private long longClaim(AuthPrincipal principal, String key) {
        Object value = principal.getClaims() == null ? null : principal.getClaims().get(key);
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? Instant.now().getEpochSecond() : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return Instant.now().getEpochSecond(); }
    }
}
