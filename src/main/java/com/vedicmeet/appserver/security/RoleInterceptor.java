package com.vedicmeet.appserver.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * Enforces @RequireRole on migrated endpoints, reproducing the Node middleware
 * responses exactly (see RequireRole javadoc). Endpoints without the annotation
 * are treated as public.
 *
 * Runs after JwtAuthFilter, which has already verified any token and set either
 * an AuthPrincipal or an auth-error marker on the request.
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthUserService authUserService;
    private final TokenRevocationService revocationService;

    @Autowired
    public RoleInterceptor(AuthUserService authUserService, TokenRevocationService revocationService) {
        this.authUserService = authUserService;
        this.revocationService = revocationService;
    }

    /** Kept for focused unit tests. */
    public RoleInterceptor(AuthUserService authUserService) {
        this.authUserService = authUserService;
        this.revocationService = null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // static resources, etc.
        }

        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true; // public endpoint
        }

        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(JwtAuthFilter.PRINCIPAL_ATTR);

        if (principal == null) {
            String err = (String) request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR);
            if ("invalid".equals(err)) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
            } else {
                write(response, HttpServletResponse.SC_OK, "Authorization token required");
            }
            return false;
        }

        if (revocationService != null && revocationService.isRevoked(principal)) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
            return false;
        }

        boolean allowed = Arrays.asList(requireRole.value()).contains(principal.getRole());
        if (!allowed) {
            write(response, HttpServletResponse.SC_OK, "Invalid token");
            return false;
        }

        // Node middleware verifies both the JWT and the current Mongo account. A valid token for a
        // deleted/disabled/missing account must not enter a controller.
        var authDocument = authUserService.loadForHttp(principal);
        if (authDocument == null) {
            String message = Role.CONSULTANT.equals(principal.getRole())
                    ? "Consultant not found" : "User not found";
            write(response, HttpServletResponse.SC_OK, message);
            return false;
        }
        if (principal.getTokenVersion() != null
                && !authUserService.tokenVersionValid(principal, authDocument)) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
            return false;
        }
        request.setAttribute(AuthUserService.AUTH_DOCUMENT_ATTR, authDocument);
        return true;
    }

    private void write(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
