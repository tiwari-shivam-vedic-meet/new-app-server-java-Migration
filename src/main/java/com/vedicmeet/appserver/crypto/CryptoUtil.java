package com.vedicmeet.appserver.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Byte-for-byte compatible with the Node service's crypto-js usage:
 *
 *   CryptoJS.AES.encrypt(JSON.stringify(data), CRYPTO_SECRET_KEY).toString()
 *   CryptoJS.AES.decrypt(reqData.replace(/ /g,"+"), CRYPTO_SECRET_KEY)
 *
 * When crypto-js is given a STRING key it treats it as a passphrase and uses the
 * OpenSSL "Salted__" scheme:
 *   - random 8-byte salt
 *   - key(32 bytes) + iv(16 bytes) derived via EVP_BytesToKey using MD5 (1 pass)
 *   - AES-256-CBC + PKCS7 padding
 *   - output = Base64( "Salted__" + salt(8) + ciphertext )
 *
 * This class reproduces exactly that, so payloads encrypted by the mobile apps
 * (reqData) decrypt here, and responses encrypted here decrypt in the apps.
 *
 * VERIFIED against the OpenSSL CLI:
 *   printf '%s' '<json>' | openssl enc -aes-256-cbc -a -A -salt -md md5 -pass pass:<key>
 * See CryptoUtilTest for the fixed test vector.
 */
@Component
public class CryptoUtil {

    private static final byte[] SALTED_MAGIC = "Salted__".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_LEN = 32; // AES-256
    private static final int IV_LEN = 16;
    private static final int SALT_LEN = 8;

    private final byte[] passphrase;
    private final SecureRandom random = new SecureRandom();

    public CryptoUtil(@Value("${vedicmeet.crypto.secret-key}") String cryptoSecretKey) {
        this.passphrase = cryptoSecretKey.getBytes(StandardCharsets.UTF_8);
    }

    /** Mirrors dataDecryption: reqData (base64 Salted__ string) -> plaintext JSON string. */
    public String decrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isEmpty()) {
            return "";
        }
        // Node does data.replace(/ /g, "+") to undo URL-decoding of '+' into ' '.
        String normalized = cipherTextBase64.replace(' ', '+');
        byte[] all = Base64.getDecoder().decode(normalized);

        if (all.length < 16 || !Arrays.equals(Arrays.copyOfRange(all, 0, 8), SALTED_MAGIC)) {
            throw new IllegalArgumentException("Ciphertext is not in OpenSSL 'Salted__' format");
        }
        byte[] salt = Arrays.copyOfRange(all, 8, 16);
        byte[] cipherBytes = Arrays.copyOfRange(all, 16, all.length);

        byte[][] keyIv = evpBytesToKey(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyIv[0], "AES"),
                    new IvParameterSpec(keyIv[1]));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed", e);
        }
    }

    /** Mirrors dataEncryption: plaintext JSON string -> base64 Salted__ string. */
    public String encrypt(String plainText) {
        byte[] salt = new byte[SALT_LEN];
        random.nextBytes(salt);
        byte[][] keyIv = evpBytesToKey(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyIv[0], "AES"),
                    new IvParameterSpec(keyIv[1]));
            byte[] enc = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[SALTED_MAGIC.length + SALT_LEN + enc.length];
            System.arraycopy(SALTED_MAGIC, 0, out, 0, SALTED_MAGIC.length);
            System.arraycopy(salt, 0, out, SALTED_MAGIC.length, SALT_LEN);
            System.arraycopy(enc, 0, out, SALTED_MAGIC.length + SALT_LEN, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed", e);
        }
    }

    /**
     * OpenSSL EVP_BytesToKey with MD5, one iteration — the exact KDF crypto-js uses
     * for passphrase mode. Produces KEY_LEN + IV_LEN bytes.
     */
    private static byte[][] evpBytesToKey(byte[] password, byte[] salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] derived = new byte[KEY_LEN + IV_LEN];
            byte[] prev = new byte[0];
            int generated = 0;
            while (generated < derived.length) {
                md5.reset();
                md5.update(prev);
                md5.update(password);
                md5.update(salt);
                prev = md5.digest();
                int toCopy = Math.min(prev.length, derived.length - generated);
                System.arraycopy(prev, 0, derived, generated, toCopy);
                generated += toCopy;
            }
            byte[] key = Arrays.copyOfRange(derived, 0, KEY_LEN);
            byte[] iv = Arrays.copyOfRange(derived, KEY_LEN, KEY_LEN + IV_LEN);
            return new byte[][]{key, iv};
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }
}
