package com.vedicmeet.appserver.auth.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Validates Google identity tokens when supplied. Email-only social login remains available behind
 * an explicit compatibility switch because existing mobile clients currently send only the email.
 */
@Component
public class GoogleSocialIdentityVerifier implements SocialIdentityVerifier {

    private final boolean googleEnabled;
    private final boolean legacyEmailEnabled;
    private final String audience;
    private final String tokenInfoUrl;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public GoogleSocialIdentityVerifier(
            @Value("${vedicmeet.auth.social.google-enabled:false}") boolean googleEnabled,
            @Value("${vedicmeet.auth.legacy-social-email-enabled:true}") boolean legacyEmailEnabled,
            @Value("${vedicmeet.auth.social.google-audience:}") String audience,
            @Value("${vedicmeet.auth.social.google-token-info-url:https://oauth2.googleapis.com/tokeninfo}") String tokenInfoUrl,
            ObjectMapper mapper) {
        this.googleEnabled = googleEnabled;
        this.legacyEmailEnabled = legacyEmailEnabled;
        this.audience = audience;
        this.tokenInfoUrl = tokenInfoUrl;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public SocialIdentity verify(AuthRequests.SocialLoginRequest request) {
        if (request.identityToken == null || request.identityToken.isBlank()) {
            if (!legacyEmailEnabled) throw AuthException.unauthorized("Provider identity token is required");
            return new SocialIdentity("legacy-email", null, normalize(request.email), false);
        }
        if (!googleEnabled || (request.provider != null && !"google".equalsIgnoreCase(request.provider))) {
            throw AuthException.unauthorized("Social provider is not configured");
        }
        try {
            String url = tokenInfoUrl + "?id_token="
                    + URLEncoder.encode(request.identityToken, StandardCharsets.UTF_8);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw AuthException.unauthorized("Invalid social identity token");
            }
            Map<String, Object> body = mapper.readValue(response.body(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            String email = normalize(string(body.get("email")));
            boolean verified = "true".equalsIgnoreCase(string(body.get("email_verified")));
            String tokenAudience = string(body.get("aud"));
            if (!verified || email == null || (!blank(audience) && !audience.equals(tokenAudience))) {
                throw AuthException.unauthorized("Invalid social identity token");
            }
            if (request.email != null && !request.email.isBlank()
                    && !email.equals(normalize(request.email))) {
                throw AuthException.unauthorized("Social identity email does not match");
            }
            return new SocialIdentity("google", string(body.get("sub")), email, true);
        } catch (AuthException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AuthException.unauthorized("Social identity verification failed");
        } catch (Exception e) {
            throw AuthException.unauthorized("Social identity verification failed");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
