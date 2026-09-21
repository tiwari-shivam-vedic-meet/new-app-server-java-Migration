package com.vedicmeet.appserver.payment;

import java.util.Map;

/** Small HTTP port used by gateway adapters so provider calls can be unit-tested offline. */
public interface PaymentHttpTransport {

    Response exchange(String method, String url, Map<String, String> headers,
                      String contentType, String body);

    final class Response {
        public final int statusCode;
        public final String body;

        public Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean is2xx() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
