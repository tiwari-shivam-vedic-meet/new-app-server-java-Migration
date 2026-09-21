package com.vedicmeet.appserver.notification;

import java.util.Map;

/** Network seam for FCM and APNs so provider behavior is testable without real credentials. */
public interface NotificationHttpTransport {

    Response exchange(String method, String url, Map<String, String> headers,
                      String contentType, String body);

    record Response(int statusCode, String body) {
        public boolean is2xx() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
