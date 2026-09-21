package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.service.AuthTokenFactory;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.JwtService;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthTokenFactoryTest {

    private final JwtService jwt = new JwtService("test-only-auth-secret");
    private final AuthTokenFactory factory = new AuthTokenFactory(jwt, 900);

    @Test
    void accountTokenCarriesBothRoleAndLegacyType() {
        ObjectId id = new ObjectId();
        Document account = new Document("_id", id)
                .append("details", new Document("phone", "9000000001").append("phonePrefix", "91"))
                .append("authTokenVersion", 3);

        Map<String, Object> claims = jwt.verify(factory.accountToken(Role.CONSULTANT, account));

        assertEquals(Role.CONSULTANT, claims.get("role"));
        assertEquals("cons", claims.get("type"));
        assertEquals(id.toHexString(), claims.get("_id"));
        assertEquals(3L, claims.get("tokenVersion"));
        assertNotNull(claims.get("jti"));
    }

    @Test
    void typeOnlyNodeLoginTokenStillMapsToConsultantRole() {
        AuthPrincipal principal = new AuthPrincipal(Map.of("type", "cons", "phone", "9000000001"));
        assertEquals(Role.CONSULTANT, principal.getRole());
    }

    @Test
    void resetTokenIsPurposeBound() {
        Document admin = new Document("_id", new ObjectId()).append("email", "admin@example.com")
                .append("role", Role.ADMIN);
        Map<String, Object> claims = jwt.verify(factory.passwordResetToken(admin, "nonce"));
        assertEquals("password_reset", claims.get("purpose"));
        assertEquals("nonce", claims.get("nonce"));
    }
}
