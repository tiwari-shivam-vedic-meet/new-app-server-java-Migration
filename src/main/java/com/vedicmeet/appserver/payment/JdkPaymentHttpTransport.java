package com.vedicmeet.appserver.payment;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** JDK HTTP client for payment providers. It never logs headers, bodies, or credentials. */
@Component
public class JdkPaymentHttpTransport implements PaymentHttpTransport {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Response exchange(String method, String url, Map<String, String> headers,
                             String contentType, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30));
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }
            if (headers != null) headers.forEach(builder::header);

            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, publisher);

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PAYMENT_PROVIDER_REQUEST_INTERRUPTED", e);
        } catch (Exception e) {
            throw new IllegalStateException("PAYMENT_PROVIDER_REQUEST_FAILED", e);
        }
    }
}
