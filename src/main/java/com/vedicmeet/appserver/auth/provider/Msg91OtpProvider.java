package com.vedicmeet.appserver.auth.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real MSG91 adapter. It is disabled by default and never logs a phone, OTP, key, or response body. */
@Component
public class Msg91OtpProvider implements OtpProvider {

    private final boolean enabled;
    private final String baseUrl;
    private final String authKey;
    private final String templateId;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public Msg91OtpProvider(
            @Value("${vedicmeet.auth.msg91.enabled:false}") boolean enabled,
            @Value("${vedicmeet.auth.msg91.base-url:https://control.msg91.com}") String baseUrl,
            @Value("${vedicmeet.auth.msg91.auth-key:}") String authKey,
            @Value("${vedicmeet.auth.msg91.template-id:}") String templateId,
            ObjectMapper mapper) {
        this(enabled, baseUrl, authKey, templateId, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    Msg91OtpProvider(boolean enabled, String baseUrl, String authKey, String templateId,
                     ObjectMapper mapper, HttpClient client) {
        this.enabled = enabled;
        this.baseUrl = stripSlash(baseUrl);
        this.authKey = authKey;
        this.templateId = templateId;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public OtpResult send(String phonePrefix, String phone) {
        requireConfigured(true);
        // MSG91 v5 send-OTP is a POST and expects the mobile as country-code+number with NO '+'.
        String path = "/api/v5/otp?template_id=" + enc(templateId) + "&mobile=" + enc(msisdn(phonePrefix, phone))
                + "&invisible=1&unicode=0";
        return call(path, "POST", "Failed to send OTP");
    }

    @Override
    public OtpResult verify(String phonePrefix, String phone, String otp) {
        requireConfigured(false);
        String path = "/api/v5/otp/verify?otp=" + enc(otp) + "&mobile=" + enc(msisdn(phonePrefix, phone));
        return call(path, "GET", "Failed to verify OTP");
    }

    @Override
    public OtpResult resend(String phonePrefix, String phone) {
        requireConfigured(false);
        String path = "/api/v5/otp/retry?retrytype=text&mobile=" + enc(msisdn(phonePrefix, phone));
        return call(path, "GET", "Failed to resend OTP");
    }

    private OtpResult call(String path, String method, String fallback) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("authkey", authKey)
                    .header("Accept", "application/json");
            HttpRequest request = ("POST".equals(method)
                    ? builder.POST(HttpRequest.BodyPublishers.noBody())
                    : builder.GET()).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = response.body() == null || response.body().isBlank()
                    ? new LinkedHashMap<>()
                    : mapper.readValue(response.body(), new TypeReference<LinkedHashMap<String, Object>>() { });
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && "success".equalsIgnoreCase(String.valueOf(body.get("type")));
            String message = body.get("message") == null ? (success ? "OTP request successful" : fallback)
                    : String.valueOf(body.get("message"));
            return new OtpResult(success, message, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(fallback);
        } catch (Exception e) {
            throw new AuthException(fallback);
        }
    }

    private void requireConfigured(boolean needTemplate) {
        if (!enabled || blank(authKey) || blank(baseUrl) || (needTemplate && blank(templateId))) {
            throw new AuthException("OTP provider is not configured");
        }
    }

    /** MSG91 expects the mobile as country-code + number with NO leading '+' (e.g. 919999999999). */
    private static String msisdn(String phonePrefix, String phone) {
        String prefix = phonePrefix == null ? "" : phonePrefix.trim();
        if (prefix.startsWith("+")) prefix = prefix.substring(1);
        return prefix + (phone == null ? "" : phone.trim());
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String stripSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
