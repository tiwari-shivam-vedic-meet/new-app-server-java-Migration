package com.vedicmeet.appserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the FULL Spring application context. This is the guard the review flagged as missing: unit
 * tests never start the context, so an {@code @Service}/{@code @Component} with an unsatisfiable
 * dependency (e.g. an interface with no Spring implementation) compiles and passes every unit test yet
 * makes the real application fail to start. This test fails fast on exactly that class of defect.
 *
 * <p>Mongo/Redis are created lazily (they connect on first use, not at context refresh), so this test
 * needs no live datastore. All secrets here are OBVIOUSLY-FAKE test values — never production keys.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // jjwt requires a >=256-bit key; supply a long, clearly-fake secret so JwtService constructs.
        "vedicmeet.jwt.secret=test-only-jwt-secret-do-not-use-0123456789abcdefghij",
        "vedicmeet.crypto.secret-key=test-only-crypto-secret-do-not-use",
        "vedicmeet.chat.server-url=http://localhost:0/api",
        "vedicmeet.socket.enabled=false",
        "spring.data.mongodb.uri=mongodb://localhost:27017/context-load-test",
        "spring.data.redis.host=localhost"
})
class AppServerContextLoadTest {

    @Test
    void contextLoads() {
        // Intentionally empty: success == the whole bean graph wired and the context started.
    }
}
