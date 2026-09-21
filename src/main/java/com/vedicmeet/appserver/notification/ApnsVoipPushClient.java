package com.vedicmeet.appserver.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** APNs token-authenticated VoIP adapter matching Node notification.sendVOIPNotificationCall. */
@Component
public class ApnsVoipPushClient {

    private static final Logger log = LoggerFactory.getLogger(ApnsVoipPushClient.class);

    private final ObjectMapper mapper;
    private final NotificationHttpTransport http;
    private final boolean enabled;
    private final String keyPath;
    private final String teamId;
    private final String keyId;
    private final String userBundleId;
    private final String consultantBundleId;
    private final String host;
    private final Clock clock;
    private volatile CachedJwt cachedJwt;

    @Autowired
    public ApnsVoipPushClient(ObjectMapper mapper, NotificationHttpTransport http,
                              @Value("${vedicmeet.notification.apns.enabled:false}") boolean enabled,
                              @Value("${vedicmeet.notification.apns.signing-key-path:}") String keyPath,
                              @Value("${vedicmeet.notification.apns.team-id:}") String teamId,
                              @Value("${vedicmeet.notification.apns.key-id:}") String keyId,
                              @Value("${vedicmeet.notification.apns.user-bundle-id:}") String userBundleId,
                              @Value("${vedicmeet.notification.apns.consultant-bundle-id:}") String consultantBundleId,
                              @Value("${vedicmeet.notification.apns.host:https://api.sandbox.push.apple.com}") String host) {
        this(mapper, http, enabled, keyPath, teamId, keyId, userBundleId,
                consultantBundleId, host, Clock.systemUTC());
    }

    ApnsVoipPushClient(ObjectMapper mapper, NotificationHttpTransport http, boolean enabled,
                       String keyPath, String teamId, String keyId, String userBundleId,
                       String consultantBundleId, String host, Clock clock) {
        this.mapper = mapper;
        this.http = http;
        this.enabled = enabled;
        this.keyPath = keyPath;
        this.teamId = teamId;
        this.keyId = keyId;
        this.userBundleId = userBundleId;
        this.consultantBundleId = consultantBundleId;
        this.host = stripTrailingSlash(host);
        this.clock = clock;
    }

    public boolean send(String voipToken, String userType, Map<String, Object> payload) {
        if (!isReady() || voipToken == null || voipToken.isBlank()) return false;
        try {
            String topic = isConsultant(userType) ? consultantBundleId : userBundleId;
            long now = clock.instant().getEpochSecond();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("aps", Map.of("content-available", 1));
            body.put("callId", value(payload, "callId"));
            body.put("callerName", value(payload, "callerName"));
            body.put("callerImage", value(payload, "callerImage"));
            body.put("sessionId", value(payload, "sessionId"));
            body.put("callMode", value(payload, "callMode"));
            body.put("callData", payload == null ? Map.of() : payload);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("authorization", "bearer " + providerJwt(now));
            headers.put("apns-topic", topic);
            headers.put("apns-push-type", "voip");
            headers.put("apns-priority", "10");
            headers.put("apns-expiration", String.valueOf(now + 30));

            NotificationHttpTransport.Response response = http.exchange("POST",
                    host + "/3/device/" + voipToken, headers, "application/json",
                    mapper.writeValueAsString(body));
            if (!response.is2xx()) {
                log.warn("APNs VoIP send rejected userType={} status={}",
                        isConsultant(userType) ? "cons" : "user", response.statusCode());
            }
            return response.is2xx();
        } catch (Exception error) {
            log.warn("APNs VoIP send failed userType={} reason={}",
                    isConsultant(userType) ? "cons" : "user", safeMessage(error));
            return false;
        }
    }

    public boolean isReady() {
        try {
            return enabled && nonBlank(keyPath) && nonBlank(teamId) && nonBlank(keyId)
                    && nonBlank(userBundleId) && nonBlank(consultantBundleId) && nonBlank(host)
                    && Files.isRegularFile(Path.of(keyPath)) && Files.isReadable(Path.of(keyPath));
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private synchronized String providerJwt(long now) throws Exception {
        CachedJwt current = cachedJwt;
        if (current != null && current.createdAtEpochSeconds() + 50 * 60 > now) return current.value();

        String header = base64Url(mapper.writeValueAsBytes(
                Map.of("alg", "ES256", "kid", keyId)));
        String claims = base64Url(mapper.writeValueAsBytes(
                Map.of("iss", teamId, "iat", now)));
        String signingInput = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(ecPrivateKey());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String jwt = signingInput + "." + base64Url(signature.sign());
        cachedJwt = new CachedJwt(jwt, now);
        return jwt;
    }

    private PrivateKey ecPrivateKey() throws Exception {
        String pem = Files.readString(Path.of(keyPath), StandardCharsets.UTF_8);
        byte[] der = Base64.getDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""));
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private Object value(Map<String, Object> payload, String key) {
        return payload == null ? null : payload.get(key);
    }

    private boolean isConsultant(String userType) {
        return userType != null && userType.trim().toLowerCase().startsWith("cons");
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record CachedJwt(String value, long createdAtEpochSeconds) {}
}
