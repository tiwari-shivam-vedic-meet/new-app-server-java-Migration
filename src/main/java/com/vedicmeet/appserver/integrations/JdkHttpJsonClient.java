package com.vedicmeet.appserver.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Production {@link HttpJsonClient} over the JDK {@link HttpClient} (no extra dependency). */
@Component
public class JdkHttpJsonClient implements HttpJsonClient {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpJsonClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String post(String url, Map<String, String> headers, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            b.header("Content-Type", "application/json");
            if (headers != null) headers.forEach(b::header);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("HTTP_POST_FAILED_STATUS_" + res.statusCode());
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HTTP POST {} interrupted", url);
            throw new RuntimeException("HTTP_POST_INTERRUPTED", e);
        } catch (Exception e) {
            log.warn("HTTP POST {} failed: {}", url, e.getMessage());
            throw new RuntimeException("HTTP_POST_FAILED", e);
        }
    }
}
