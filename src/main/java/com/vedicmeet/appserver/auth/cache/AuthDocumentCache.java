package com.vedicmeet.appserver.auth.cache;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Node-compatible ten-minute read-through authentication cache with explicit invalidation. */
@Service
public class AuthDocumentCache {

    private final CacheService cache;
    private final long ttlSeconds;

    public AuthDocumentCache(CacheService cache,
                             @Value("${vedicmeet.auth.cache-ttl-seconds:600}") long ttlSeconds) {
        this.cache = cache;
        this.ttlSeconds = ttlSeconds;
    }

    public Document get(AuthPrincipal principal, boolean socket) {
        String key = key(principal, socket);
        if (key == null) return null;
        Document document = cache.get(key, Document.class);
        rehydrateId(document);
        return document;
    }

    public void put(AuthPrincipal principal, boolean socket, Document document) {
        String key = key(principal, socket);
        if (key != null && document != null) cache.set(key, document, ttlSeconds);
    }

    public void invalidate(String role, Document document) {
        if (document == null) return;
        Document details = document.get("details") instanceof Document d ? d : new Document();
        String phone = string(details.get("phone"));
        String prefix = string(details.get("phonePrefix"));
        String email = first(string(details.get("email")), string(document.get("email")));
        if (Role.USER.equals(role)) {
            if (email != null) cache.invalidate("auth:user:email:" + email + "*");
            if (phone != null) cache.invalidate("auth:user:phone:" + safe(prefix) + ":" + phone + "*");
        } else if (Role.CONSULTANT.equals(role)) {
            if (phone != null) cache.invalidate("auth:consultant:phone:" + safe(prefix) + ":" + phone + "*");
            if (email != null) cache.invalidate("auth:consultant:email:" + email + "*");
        } else if ((Role.ADMIN.equals(role) || Role.SUB_ADMIN.equals(role)) && document.get("_id") != null) {
            cache.invalidate("auth:admin:" + document.get("_id") + "*");
        }
    }

    public String key(AuthPrincipal principal, boolean socket) {
        if (principal == null) return null;
        String base = null;
        if (Role.USER.equals(principal.getRole())) {
            if (principal.getEmail() != null && !principal.getEmail().isBlank()) {
                base = "auth:user:email:" + principal.getEmail();
            } else if (principal.getPhone() != null) {
                base = "auth:user:phone:" + safe(principal.getPhonePrefix()) + ":" + principal.getPhone();
            }
        } else if (Role.CONSULTANT.equals(principal.getRole())) {
            if (principal.getPhone() != null) {
                base = "auth:consultant:phone:" + safe(principal.getPhonePrefix()) + ":" + principal.getPhone();
            } else if (principal.getEmail() != null) {
                base = "auth:consultant:email:" + principal.getEmail();
            }
        } else if ((Role.ADMIN.equals(principal.getRole()) || Role.SUB_ADMIN.equals(principal.getRole()))
                && principal.getId() != null) {
            base = "auth:admin:" + principal.getId();
        }
        return socket && base != null ? base + ":socket" : base;
    }

    private void rehydrateId(Document document) {
        if (document == null) return;
        Object id = document.get("_id");
        if (id instanceof String value && ObjectId.isValid(value)) document.put("_id", new ObjectId(value));
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private String first(String a, String b) { return a != null && !a.isBlank() ? a : b; }
}
