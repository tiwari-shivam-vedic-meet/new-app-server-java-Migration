package com.vedicmeet.appserver.notification;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** JDK HTTP/2 transport. Headers and bodies are deliberately never logged. */
@Component
public class JdkNotificationHttpTransport implements NotificationHttpTransport {

    private final HttpClient client;

    public JdkNotificationHttpTransport() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    JdkNotificationHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public Response exchange(String method, String url, Map<String, String> headers,
                             String contentType, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30));
            if (contentType != null && !contentType.isBlank()) {
                request.header("Content-Type", contentType);
            }
            if (headers != null) headers.forEach(request::header);
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NOTIFICATION_PROVIDER_REQUEST_INTERRUPTED", interrupted);
        } catch (Exception error) {
            throw new IllegalStateException("NOTIFICATION_PROVIDER_REQUEST_FAILED", error);
        }
    }
}
