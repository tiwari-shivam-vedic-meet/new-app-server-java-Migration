package com.vedicmeet.appserver.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated AuthPrincipal into a controller method parameter:
 *
 *   @GetMapping("/me")
 *   @RequireRole(Role.USER)
 *   public ApiResponse<?> me(@CurrentUser AuthPrincipal user) { ... }
 *
 * Resolved by CurrentUserArgumentResolver from the request attribute set by
 * JwtAuthFilter. Cleaner than reading request attributes by hand in every handler.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
