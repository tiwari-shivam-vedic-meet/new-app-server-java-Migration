package com.vedicmeet.appserver.crypto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Object-level helpers over CryptoUtil, mirroring how the Node validators layer
 * transparently handles the encrypted envelope:
 *
 *   // request:  body = { "reqData": "<base64>" }  ->  decrypt -> JSON -> object
 *   // response: object -> JSON -> encrypt -> "<base64>"
 *
 * Controllers for endpoints that use encryption call decryptRequest(...) on the way
 * in and encryptResponse(...) on the way out; the plaintext object never leaves the
 * process. Endpoints that don't encrypt simply skip this.
 */
@Service
public class CryptoService {

    private final CryptoUtil cryptoUtil;
    private final ObjectMapper mapper;

    public CryptoService(CryptoUtil cryptoUtil, ObjectMapper mapper) {
        this.cryptoUtil = cryptoUtil;
        this.mapper = mapper;
    }

    /** Decrypt a reqData string into a typed object. */
    public <T> T decrypt(String reqData, Class<T> type) {
        try {
            String json = cryptoUtil.decrypt(reqData);
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decrypt request payload", e);
        }
    }

    /** Decrypt a reqData string into a generic map (when the shape is dynamic). */
    public Map<String, Object> decryptToMap(String reqData) {
        try {
            String json = cryptoUtil.decrypt(reqData);
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decrypt request payload", e);
        }
    }

    /** Encrypt an object into a crypto-js-compatible reqData string. */
    public String encrypt(Object payload) {
        try {
            return cryptoUtil.encrypt(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt response payload", e);
        }
    }
}
