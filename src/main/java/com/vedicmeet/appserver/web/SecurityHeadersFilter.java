package com.vedicmeet.appserver.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Baseline security response headers on every response. Cheap, defensive, and
 * expected by security reviews. Runs early so headers are present even on errors.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");

        String path = request.getRequestURI();
        if (isSwagger(path)) {
            // Swagger UI is a real web page (its own CSS/JS/fonts). The strict API CSP would block
            // those assets, so serve a scoped, still-conservative policy for the docs paths only.
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                            + "font-src 'self' data:; frame-ancestors 'none'");
        } else {
            response.setHeader("Cache-Control", "no-store");
            // This is a JSON API; a strict CSP prevents any accidental HTML/script rendering.
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        }
        filterChain.doFilter(request, response);
    }

    /** Swagger UI + OpenAPI spec paths that must be allowed to load their own web assets. */
    private boolean isSwagger(String path) {
        return path != null && (path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html"));
    }
}
