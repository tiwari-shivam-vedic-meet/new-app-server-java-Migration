package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthDocumentCacheTest {

    @Test
    void usesExactNodePhoneKeyAndSeparateSocketEntry() {
        AuthDocumentCache cache = new AuthDocumentCache(mock(CacheService.class), 600);
        AuthPrincipal principal = new AuthPrincipal(Map.of(
                "role", Role.USER, "phone", "9000000001", "phonePrefix", "91"));

        assertEquals("auth:user:phone:91:9000000001", cache.key(principal, false));
        assertEquals("auth:user:phone:91:9000000001:socket", cache.key(principal, true));
    }

    @Test
    void emailAliasTakesPriorityForLegacyUserToken() {
        AuthDocumentCache cache = new AuthDocumentCache(mock(CacheService.class), 600);
        AuthPrincipal principal = new AuthPrincipal(Map.of(
                "role", Role.USER, "email", "user@example.com",
                "phone", "9000000001", "phonePrefix", "91"));

        assertEquals("auth:user:email:user@example.com", cache.key(principal, false));
    }

    @Test
    void cachedStringObjectIdIsRehydratedForMongoOperations() {
        CacheService delegate = mock(CacheService.class);
        AuthDocumentCache cache = new AuthDocumentCache(delegate, 600);
        AuthPrincipal principal = new AuthPrincipal(Map.of(
                "role", Role.ADMIN, "_id", new ObjectId().toHexString()));
        Document cached = new Document("_id", principal.getId()).append("status", true);
        when(delegate.get(cache.key(principal, false), Document.class)).thenReturn(cached);

        Document result = cache.get(principal, false);

        assertInstanceOf(ObjectId.class, result.get("_id"));
    }

    @Test
    void accountChangeInvalidatesBothHttpAndSocketVariants() {
        CacheService delegate = mock(CacheService.class);
        AuthDocumentCache cache = new AuthDocumentCache(delegate, 600);
        Document consultant = new Document("_id", new ObjectId()).append("details",
                new Document("phone", "9000000002").append("phonePrefix", "91")
                        .append("email", "consultant@example.com"));

        cache.invalidate(Role.CONSULTANT, consultant);

        verify(delegate).invalidate(eq("auth:consultant:phone:91:9000000002*"));
        verify(delegate).invalidate(eq("auth:consultant:email:consultant@example.com*"));
    }
}
