package com.vedicmeet.appserver.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
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

/**
 * FCM HTTP v1 adapter matching the two Firebase Admin apps used by Node. Credentials are loaded
 * lazily from environment-configured service-account files; no key material is logged or cached
 * outside this process.
 */
@Component
public class FcmPushClient {

    private static final Logger log = LoggerFactory.getLogger(FcmPushClient.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private final ObjectMapper mapper;
    private final NotificationHttpTransport http;
    private final boolean enabled;
    private final String userCredentialsPath;
    private final String consultantCredentialsPath;
    private final String defaultTokenUrl;
    private final String apiBaseUrl;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new LinkedHashMap<>();

    @Autowired
    public FcmPushClient(ObjectMapper mapper, NotificationHttpTransport http,
                         @Value("${vedicmeet.notification.fcm.enabled:false}") boolean enabled,
                         @Value("${vedicmeet.notification.fcm.user-service-account-path:}") String userCredentialsPath,
                         @Value("${vedicmeet.notification.fcm.consultant-service-account-path:}") String consultantCredentialsPath,
                         @Value("${vedicmeet.notification.fcm.token-url:https://oauth2.googleapis.com/token}") String defaultTokenUrl,
                         @Value("${vedicmeet.notification.fcm.api-base-url:https://fcm.googleapis.com}") String apiBaseUrl) {
        this(mapper, http, enabled, userCredentialsPath, consultantCredentialsPath,
                defaultTokenUrl, apiBaseUrl, Clock.systemUTC());
    }

    FcmPushClient(ObjectMapper mapper, NotificationHttpTransport http, boolean enabled,
                  String userCredentialsPath, String consultantCredentialsPath,
                  String defaultTokenUrl, String apiBaseUrl, Clock clock) {
        this.mapper = mapper;
        this.http = http;
        this.enabled = enabled;
        this.userCredentialsPath = userCredentialsPath;
        this.consultantCredentialsPath = consultantCredentialsPath;
        this.defaultTokenUrl = defaultTokenUrl;
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.clock = clock;
    }

    public boolean send(String userType, Map<String, Object> payload) {
        if (!isReadyFor(userType) || payload == null) return false;
        try {
            Credentials credentials = load(credentialsPath(userType));
            String accessToken = accessToken(credentials);
            String url = apiBaseUrl + "/v1/projects/" + path(credentials.projectId()) + "/messages:send";
            NotificationHttpTransport.Response response = http.exchange("POST", url,
                    Map.of("Authorization", "Bearer " + accessToken),
                    "application/json", mapper.writeValueAsString(payload));
            if (!response.is2xx()) {
                log.warn("FCM send rejected userType={} status={}", normalized(userType), response.statusCode());
            }
            return response.is2xx();
        } catch (Exception error) {
            log.warn("FCM send failed userType={} reason={}", normalized(userType), safeMessage(error));
            return false;
        }
    }

    public boolean isReady() {
        return isReadyFor("user") && isReadyFor("cons");
    }

    public boolean isReadyFor(String userType) {
        String value = credentialsPath(userType);
        try {
            return enabled && value != null && !value.isBlank()
                    && Files.isRegularFile(Path.of(value)) && Files.isReadable(Path.of(value));
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private synchronized String accessToken(Credentials credentials) throws Exception {
        long now = clock.instant().getEpochSecond();
        CachedToken cached = tokens.get(credentials.cacheKey());
        if (cached != null && cached.expiresAtEpochSeconds() - 60 > now) return cached.value();

        String assertion = serviceAccountAssertion(credentials, now);
        String form = "grant_type=" + form(GRANT) + "&assertion=" + form(assertion);
        NotificationHttpTransport.Response response = http.exchange("POST", credentials.tokenUrl(),
                Map.of(), "application/x-www-form-urlencoded", form);
        if (!response.is2xx()) {
            throw new IllegalStateException("FCM_OAUTH_STATUS_" + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        String token = text(json, "access_token");
        if (token.isBlank()) throw new IllegalStateException("FCM_OAUTH_TOKEN_MISSING");
        long expiresIn = json.path("expires_in").asLong(3600);
        tokens.put(credentials.cacheKey(), new CachedToken(token, now + Math.max(120, expiresIn)));
        return token;
    }

    private String serviceAccountAssertion(Credentials credentials, long now) throws Exception {
        String header = base64Url(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", credentials.tokenUrl());
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        String payload = base64Url(mapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(rsaPrivateKey(credentials.privateKeyPem()));
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature.sign());
    }

    private Credentials load(String file) throws Exception {
        JsonNode json = mapper.readTree(Files.readString(Path.of(file), StandardCharsets.UTF_8));
        String tokenUrl = text(json, "token_uri");
        if (tokenUrl.isBlank()) tokenUrl = defaultTokenUrl;
        Credentials result = new Credentials(file, text(json, "client_email"),
                text(json, "private_key"), text(json, "project_id"), tokenUrl);
        if (result.clientEmail().isBlank() || result.privateKeyPem().isBlank()
                || result.projectId().isBlank() || result.tokenUrl().isBlank()) {
            throw new IllegalStateException("FCM_SERVICE_ACCOUNT_INCOMPLETE");
        }
        return result;
    }

    private PrivateKey rsaPrivateKey(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String credentialsPath(String userType) {
        return "cons".equals(normalized(userType)) ? consultantCredentialsPath : userCredentialsPath;
    }

    private String normalized(String userType) {
        String value = userType == null ? "" : userType.trim().toLowerCase();
        return value.startsWith("cons") ? "cons" : "user";
    }

    private String text(JsonNode json, String key) {
        JsonNode value = json == null ? null : json.get(key);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record Credentials(String cacheKey, String clientEmail, String privateKeyPem,
                               String projectId, String tokenUrl) {}
    private record CachedToken(String value, long expiresAtEpochSeconds) {}
}
