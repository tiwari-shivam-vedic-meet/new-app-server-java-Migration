package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.JwtService;
import com.vedicmeet.appserver.security.TokenRevocationService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Socket handshake JWT-auth contract for {@link SocketHandshakeAuthenticator}. Signs real tokens with
 * the same {@link JwtService} the REST layer uses and asserts socket auth accepts exactly those (and
 * rejects missing/tampered ones). Shadow-only infra for Prompt F.
 */
class SocketHandshakeAuthenticatorTest {

    private static final String SECRET = "vedicmeet_test_jwt_secret_do_not_use_in_prod";
    private final JwtService jwt = new JwtService(SECRET);
    private final SocketHandshakeAuthenticator auth = new SocketHandshakeAuthenticator(jwt);

    private String token() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", "9000000001");
        claims.put("phonePrefix", "+91");
        claims.put("role", "user");
        return jwt.sign(claims);
    }

    @Test
    void validHandshakeToken_resolvesPrincipal() {
        AuthPrincipal p = auth.authenticate(token());
        assertEquals("user", p.getRole());
        assertEquals("9000000001", p.getPhone());
    }

    @Test
    void bearerPrefixIsStripped() {
        AuthPrincipal p = auth.authenticate("Bearer " + token());
        assertEquals("user", p.getRole());
    }

    @Test
    void missingToken_isRejected() {
        assertThrows(SocketHandshakeAuthenticator.SocketAuthException.class, () -> auth.authenticate(null));
        assertThrows(SocketHandshakeAuthenticator.SocketAuthException.class, () -> auth.authenticate("  "));
    }

    @Test
    void tamperedToken_isRejected() {
        String t = token();
        String tampered = t.substring(0, t.length() - 2) + "XX";
        assertThrows(SocketHandshakeAuthenticator.SocketAuthException.class, () -> auth.authenticate(tampered));
    }

    @Test
    void revokedHttpTokenIsAlsoRejectedBySocketHandshake() {
        TokenRevocationService revocations = mock(TokenRevocationService.class);
        SocketHandshakeAuthenticator secured = new SocketHandshakeAuthenticator(jwt, revocations);
        when(revocations.isRevoked(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        assertThrows(SocketHandshakeAuthenticator.SocketAuthException.class,
                () -> secured.authenticate(token()));
    }
}
