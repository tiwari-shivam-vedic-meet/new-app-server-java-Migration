package com.vedicmeet.appserver.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the Java AES is byte-compatible with the Node crypto-js usage.
 * The fixture ciphertext was produced by the OpenSSL CLI (independent of this code):
 *   printf '%s' '<json>' | openssl enc -aes-256-cbc -a -A -salt -md md5 -pass pass:<key>
 * See src/test/resources/parity-fixtures.md.
 */
class CryptoUtilTest {

    private static final String TEST_KEY = "vedicmeet_test_key_do_not_use_in_prod";
    private static final String PLAINTEXT = "{\"phone\":\"9000000001\",\"phonePrefix\":\"+91\",\"foo\":\"bar\",\"n\":42}";
    private static final String REQDATA =
            "U2FsdGVkX18LSGkUq4RA+7GtvXS/WSmZ0Xqzznz6k210gwMVlz+hMErEHjkFFqVYd9zahgjRicE2bj6YL7hIzy4bWOPbi0nvPmGsWe2VhVA=";

    private final CryptoUtil crypto = new CryptoUtil(TEST_KEY);

    @Test
    void decryptsCryptoJsFixture() {
        assertEquals(PLAINTEXT, crypto.decrypt(REQDATA),
                "Java must decrypt a crypto-js/OpenSSL 'Salted__' payload to the exact plaintext");
    }

    @Test
    void handlesUrlDecodedSpaces() {
        // Mobile clients sometimes send '+' as ' ' after URL decoding; Node undoes this.
        String spaced = REQDATA.replace('+', ' ');
        assertEquals(PLAINTEXT, crypto.decrypt(spaced));
    }

    @Test
    void roundTripsEncryptThenDecrypt() {
        String payload = "{\"hello\":\"world\",\"num\":123,\"nested\":{\"a\":true}}";
        String enc = crypto.encrypt(payload);
        assertTrue(enc.startsWith("U2FsdGVkX1"), "output must be in OpenSSL Salted__ base64 format");
        assertEquals(payload, crypto.decrypt(enc));
    }

    @Test
    void encryptProducesFreshSaltEachTime() {
        String p = "{\"x\":1}";
        assertNotEquals(crypto.encrypt(p), crypto.encrypt(p),
                "random salt means ciphertext differs each call (matches crypto-js)");
    }
}
