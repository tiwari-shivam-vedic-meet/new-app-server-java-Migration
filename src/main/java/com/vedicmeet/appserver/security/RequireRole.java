package com.vedicmeet.appserver.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which role(s) may call an endpoint, replacing the per-route Node
 * middleware (userAuthMiddleware / consAuthMiddleware / authorization).
 *
 * Usage:
 *   @RequireRole(Role.USER)                       // user-only
 *   @RequireRole({Role.USER, Role.CONSULTANT})    // either (like `authorization`)
 *
 * Enforced by RoleInterceptor, which reproduces Node's exact responses:
 *   - no token           -> HTTP 200 { success:false, "Authorization token required" }
 *   - invalid/expired    -> HTTP 401 { success:false, "Invalid token" }
 *   - wrong role         -> HTTP 200 { success:false, "Invalid token" }
 *
 * Absence of the annotation means the endpoint is public (e.g. /v2/health).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    String[] value();
}
