package com.vedicmeet.appserver.integrations;

import java.util.Map;

/**
 * Tiny JSON-over-HTTP POST seam shared by the third-party clients ({@link InteraktClient},
 * {@link CleverTapClient}). Behind a port so each client's URL/headers/body construction is
 * unit-testable without real network calls; {@link JdkHttpJsonClient} is the production impl.
 */
public interface HttpJsonClient {

    /** POST jsonBody to url with the given headers; returns the raw response body. */
    String post(String url, Map<String, String> headers, String jsonBody);
}
