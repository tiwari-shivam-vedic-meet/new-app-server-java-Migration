package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.JwtService;
import com.vedicmeet.appserver.security.TokenRevocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ⚠ SHADOW-ONLY infra for Prompt F (Socket.IO). Verifies the JWT that the client sends in the
 * Socket.IO handshake (`handshake.auth.token`, per COPILOT_HANDOFF.md Prompt F) and resolves it to an
 * {@link AuthPrincipal} — reusing the same HS256 {@link JwtService} the REST layer uses, so socket
 * auth and HTTP auth accept exactly the same tokens.
 *
 * This is the library-agnostic, testable core of the handshake gate. The actual Socket.IO server
 * (namespace `/app`, a `ConnectListener` that reads the handshake token and calls this, then the
 * event catalog) is a thin wiring layer over whichever Java Socket.IO server library is chosen
 * (e.g. netty-socketio) — kept out of the compiled tree until that dependency + a Node peer to verify
 * protocol parity are available (see PROMPT_F_STATUS.md).
 */
@Component
public class SocketHandshakeAuthenticator {

    private final JwtService jwt;
    private final TokenRevocationService revocations;

    @Autowired
    public SocketHandshakeAuthenticator(JwtService jwt, TokenRevocationService revocations) {
        this.jwt = jwt;
        this.revocations = revocations;
    }

    /** Kept for the pure JWT unit tests. */
    public SocketHandshakeAuthenticator(JwtService jwt) {
        this.jwt = jwt;
        this.revocations = null;
    }

    public static class SocketAuthException extends RuntimeException {
        public SocketAuthException(String message) { super(message); }
    }

    /**
     * @param rawToken the raw handshake token (optionally "Bearer &lt;jwt&gt;").
     * @return the authenticated principal (phone/phonePrefix/role + claims).
     * @throws SocketAuthException if the token is missing or fails verification (→ reject the connection).
     */
    public AuthPrincipal authenticate(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new SocketAuthException("Missing handshake auth token");
        }
        String token = rawToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        Map<String, Object> claims;
        try {
            claims = jwt.verify(token);
        } catch (Exception e) {
            throw new SocketAuthException("Invalid handshake auth token");
        }
        AuthPrincipal principal = new AuthPrincipal(claims);
        if (revocations != null && revocations.isRevoked(principal)) {
            throw new SocketAuthException("Invalid handshake auth token");
        }
        return principal;
    }
}
