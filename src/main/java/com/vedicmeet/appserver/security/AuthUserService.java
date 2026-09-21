package com.vedicmeet.appserver.security;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Loads the full user/consultant document for an authenticated caller — the Java
 * equivalent of the Node middleware's AuthCache lookup that populates req.user.
 *
 * The JWT only carries {phone, phonePrefix, role} (+ optional email). Endpoints that
 * need the caller's Mongo document (e.g. explore uses user._id for isLiked/isBookmark;
 * banner uses many user fields) call this to resolve it, mirroring how Node's
 * `authorization` middleware attaches req.user.
 *
 * Lookup mirrors auths.js:
 *   user:       { 'details.phone', 'details.phonePrefix', isDeleted:false } (or by email)
 *   consultant: { 'details.phone', 'details.phonePrefix' }
 *
 * Node reads this through a Redis cache (AuthCache). Java now uses the same read-through
 * cache key families and validates cached status/approval/deletion before accepting them.
 */
@Service
public class AuthUserService {

    /** Full authenticated Mongo document cached on the current HTTP request. */
    public static final String AUTH_DOCUMENT_ATTR = "authDocument";

    private final MongoTemplate mongo;
    private final AuthDocumentCache cache;

    @Autowired
    public AuthUserService(MongoTemplate mongo, AuthDocumentCache cache) {
        this.mongo = mongo;
        this.cache = cache;
    }

    /** Kept for focused unit tests and non-Spring callers. */
    public AuthUserService(MongoTemplate mongo) {
        this.mongo = mongo;
        this.cache = null;
    }

    /**
     * Resolve the caller's HTTP-auth document. RoleInterceptor has normally already loaded it;
     * reuse that request attribute so controllers do not issue a second Mongo query.
     */
    public Document load(AuthPrincipal principal) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            Object cached = attrs.getRequest().getAttribute(AUTH_DOCUMENT_ATTR);
            if (cached instanceof Document document) {
                return document;
            }
        }
        return loadForHttp(principal);
    }

    /** Mirrors Node HTTP AuthCache: user lookup includes isDeleted:false AND status:true. */
    public Document loadForHttp(AuthPrincipal principal) {
        return loadInternal(principal, true, false);
    }

    /** Socket auth also validates current status; this closes the Node cache/status gap safely. */
    public Document loadForSocket(AuthPrincipal principal) {
        return loadInternal(principal, true, true);
    }

    private Document loadInternal(AuthPrincipal principal, boolean requireActiveUser, boolean socket) {
        if (principal == null) {
            return null;
        }
        Document cached = cache == null ? null : cache.get(principal, socket);
        if (cached != null && accountStateValid(principal, cached)) return cached;

        Document found;
        if (Role.CONSULTANT.equals(principal.getRole())) {
            Document identity = principal.getEmail() != null
                    ? new Document("$or", java.util.List.of(
                            new Document("details.email", principal.getEmail()),
                            new Document("email", principal.getEmail())))
                    : new Document("details.phone", principal.getPhone())
                            .append("details.phonePrefix", principal.getPhonePrefix());
            found = mongo.getCollection(Collections.CONSULTANTS)
                    .find(identity.append("isDeleted", false).append("status", true)
                            .append("isAdminVerify", true))
                    .first();
        } else if (Role.ADMIN.equals(principal.getRole()) || Role.SUB_ADMIN.equals(principal.getRole())) {
            if (principal.getId() == null || !ObjectId.isValid(principal.getId())) return null;
            found = mongo.getCollection(Collections.ADMINS)
                    .find(new Document("_id", new ObjectId(principal.getId())).append("status", true))
                    .first();
        } else {
            Document filter;
            if (principal.getEmail() != null) {
                filter = new Document("$or", java.util.List.of(
                        new Document("details.email", principal.getEmail()),
                        new Document("email", principal.getEmail())))
                        .append("isDeleted", false);
            } else {
                filter = new Document("details.phone", principal.getPhone())
                        .append("details.phonePrefix", principal.getPhonePrefix())
                        .append("isDeleted", false);
            }
            if (requireActiveUser) filter.append("status", true);
            found = mongo.getCollection(Collections.USERS).find(filter).first();
        }
        if (found != null && cache != null) cache.put(principal, socket, found);
        return found;
    }

    public boolean tokenVersionValid(AuthPrincipal principal, Document account) {
        Integer tokenVersion = principal == null ? null : principal.getTokenVersion();
        if (tokenVersion == null) return true; // compatibility with tokens already issued by Node.
        Object stored = account == null ? null : account.get("authTokenVersion");
        int current = stored instanceof Number number ? number.intValue() : 0;
        return tokenVersion == current;
    }

    private boolean accountStateValid(AuthPrincipal principal, Document account) {
        if (account == null) return false;
        if (Boolean.FALSE.equals(account.get("status"))) return false;
        if (Role.USER.equals(principal.getRole()) && Boolean.TRUE.equals(account.get("isDeleted"))) return false;
        if (Role.CONSULTANT.equals(principal.getRole())) {
            return !Boolean.TRUE.equals(account.get("isDeleted"))
                    && Boolean.TRUE.equals(account.get("isAdminVerify"));
        }
        return true;
    }
}
