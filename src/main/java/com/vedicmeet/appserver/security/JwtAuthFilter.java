package com.vedicmeet.appserver.security;

import com.vedicmeet.appserver.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Map;
import java.util.List;

/**
 * Token extraction mirrors the Node middle-wares.js exactly:
 *   - userAuthMiddleware  : reads header 'vm-user-auth'
 *   - consAuthMiddleware  : reads 'authorization' else 'vm-user-auth' (raw token)
 *   - authorization       : reads 'authorization' else 'vm-user-auth', then
 *                           splits on space and takes [1] (Bearer xxx) else [0]
 *
 * This filter is permissive: it verifies a token IF present and attaches an
 * AuthPrincipal request attribute, but does not block unauthenticated routes.
 * Per-endpoint role enforcement (matching each Node route's middleware) is added
 * as endpoints are migrated. Public routes like /v2/health are unaffected.
 *
 * The Node role/expiry failures return HTTP 200 {success:false}; a thrown/invalid
 * token returns HTTP 401 {success:false}. Endpoint guards reproduce that; this
 * filter only parses.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTR = "authPrincipal";
    /** "missing" (no token header) or "invalid" (present but failed verify). */
    public static final String AUTH_ERROR_ATTR = "authError";

    private final JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null || token.isEmpty()) {
            request.setAttribute(AUTH_ERROR_ATTR, "missing");
        } else {
            try {
                Map<String, Object> claims = jwtService.verify(token);
                AuthPrincipal principal = new AuthPrincipal(claims);
                request.setAttribute(PRINCIPAL_ATTR, principal);
                List<SimpleGrantedAuthority> authorities = principal.getRole() == null
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_" + principal.getRole()));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, token, authorities));
            } catch (Exception e) {
                // Present but bad token — Node maps this to HTTP 401.
                request.setAttribute(AUTH_ERROR_ATTR, "invalid");
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("authorization");
        String vmUser = request.getHeader("vm-user-auth");
        String raw = (auth != null && !auth.isEmpty()) ? auth : vmUser;
        if (raw == null) return null;
        String[] parts = raw.split(" ");
        return parts.length > 1 ? parts[1] : parts[0];
    }

    /** Helper for endpoint guards to write the Node-style 200 {success:false}. */
    public void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
