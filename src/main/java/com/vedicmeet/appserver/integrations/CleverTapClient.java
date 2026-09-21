package com.vedicmeet.appserver.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of Node {@code CleverTapClient.uploadEvent} (utils/classes/clever.js). Posts to the CleverTap
 * upload API with the account id + passcode in headers. Credentials are injected from config
 * (env: CLEVER_TAP_PROJECT_ID / CLEVER_TAP_PASSCODE) — NEVER hardcoded.
 *
 * SHADOW-ONLY: wire into the recharge/consult events once reviewed.
 */
@Component
public class CleverTapClient {

    private static final String BASE_URL = "https://api.clevertap.com/1";

    private final String accountId;
    private final String passcode;
    private final HttpJsonClient http;
    private final ObjectMapper mapper;

    public CleverTapClient(@Value("${CLEVER_TAP_PROJECT_ID:}") String accountId,
                           @Value("${CLEVER_TAP_PASSCODE:}") String passcode,
                           HttpJsonClient http, ObjectMapper mapper) {
        this.accountId = accountId;
        this.passcode = passcode;
        this.http = http;
        this.mapper = mapper;
    }

    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-CleverTap-Account-Id", accountId);
        h.put("X-CleverTap-Passcode", passcode);
        h.put("Content-Type", "application/json; charset=utf-8");
        return h;
    }

    /** Node body: { d: [ { identity, type:'event', evtName, evtData } ] }. */
    public Map<String, Object> buildEventBody(String identity, String evtName, Map<String, Object> evtData) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("identity", identity);
        event.put("type", "event");
        event.put("evtName", evtName);
        event.put("evtData", evtData == null ? new LinkedHashMap<>() : evtData);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("d", Arrays.asList(event));
        return body;
    }

    /** Node body for uploadProfile: { d: [ { identity, type:'profile', profileData } ] }. */
    public Map<String, Object> buildProfileBody(String identity, Map<String, Object> profileData) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("identity", identity);
        profile.put("type", "profile");
        profile.put("profileData", profileData == null ? new LinkedHashMap<>() : profileData);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("d", Arrays.asList(profile));
        return body;
    }

    public String endpoint() {
        return BASE_URL + "/upload";
    }

    /** Optional analytics must fail closed when credentials are not configured. */
    public boolean isReady() {
        return accountId != null && !accountId.isBlank()
                && passcode != null && !passcode.isBlank();
    }

    public void uploadEvent(String identity, String evtName, Map<String, Object> evtData) {
        try {
            http.post(endpoint(), headers(), mapper.writeValueAsString(buildEventBody(identity, evtName, evtData)));
        } catch (Exception e) {
            throw new RuntimeException("CLEVERTAP_UPLOAD_FAILED", e);
        }
    }

    public void uploadProfile(String identity, Map<String, Object> profileData) {
        try {
            http.post(endpoint(), headers(), mapper.writeValueAsString(buildProfileBody(identity, profileData)));
        } catch (Exception e) {
            throw new RuntimeException("CLEVERTAP_UPLOAD_FAILED", e);
        }
    }
}
