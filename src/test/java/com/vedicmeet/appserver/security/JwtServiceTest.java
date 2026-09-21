package com.vedicmeet.appserver.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the Java JWT is HS256-compatible with the Node jsonwebtoken usage.
 * The fixture token was signed by a standalone Python HMAC script (independent of
 * this code) using the TEST secret. See src/test/resources/parity-fixtures.md.
 */
class JwtServiceTest {

    private static final String TEST_SECRET = "vedicmeet_test_jwt_secret_do_not_use_in_prod";
    // Node/jsonwebtoken-format token, exp = year 2100 so it never expires in CI.
    private static final String NODE_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJwaG9uZSI6IjkwMDAwMDAwMDEiLCJwaG9uZVByZWZpeCI6Iis5MSIsInJvbGUiOiJ1c2VyIiwiaWF0IjoxNzU2NTAwMDAwLCJleHAiOjQxMDI0NDQ4MDB9." +
            "9E0-CRRnFpWszHGRIW2pephE6HlTCFJZLOaRmunHAl8";

    private final JwtService jwt = new JwtService(TEST_SECRET);

    @Test
    void verifiesTokenSignedByNode() {
        Map<String, Object> claims = jwt.verify(NODE_TOKEN);
        assertEquals("9000000001", claims.get("phone"));
        assertEquals("+91", claims.get("phonePrefix"));
        assertEquals("user", claims.get("role"));
    }

    @Test
    void rejectsTamperedToken() {
        String tampered = NODE_TOKEN.substring(0, NODE_TOKEN.length() - 2) + "XX";
        assertThrows(RuntimeException.class, () -> jwt.verify(tampered));
    }

    @Test
    void rejectsWrongSecret() {
        JwtService other = new JwtService("some_other_secret");
        assertThrows(RuntimeException.class, () -> other.verify(NODE_TOKEN));
    }

    @Test
    void roundTripsSignThenVerify() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", "9111111111");
        claims.put("phonePrefix", "+91");
        claims.put("role", Role.CONSULTANT);
        String token = jwt.sign(claims);
        Map<String, Object> back = jwt.verify(token);
        assertEquals("9111111111", back.get("phone"));
        assertEquals(Role.CONSULTANT, back.get("role"));
        assertTrue(back.containsKey("iat"));
        assertTrue(back.containsKey("exp"));
    }

    @Test
    void rejectsExpiredTokenAtBoundary() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", "9111111111");
        claims.put("phonePrefix", "+91");
        claims.put("role", Role.USER);
        assertThrows(RuntimeException.class, () -> jwt.verify(jwt.sign(claims, 0)));
    }
}
