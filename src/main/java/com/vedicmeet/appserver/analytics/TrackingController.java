package com.vedicmeet.appserver.analytics;

import com.vedicmeet.appserver.migration.MigrationWrite;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Contract-compatible /track routes, namespaced under /v2 for strangler rollout. */
@RestController
@RequestMapping("/v2/track")
public class TrackingController {
    private final TrackingService service;
    private final String fallback;

    public TrackingController(TrackingService service,
            @org.springframework.beans.factory.annotation.Value(
                    "${vedicmeet.tracking.website-install-url:https://vedicmeet.com/install}") String fallback) {
        this.service = service; this.fallback = fallback;
    }

    @GetMapping("/install") @MigrationWrite
    public ResponseEntity<Void> install(@RequestParam Map<String, String> query, HttpServletRequest request) {
        String target;
        try { target = service.installRedirect(new LinkedHashMap<>(query), context(request)); }
        catch (Exception failure) { target = fallback; }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @PostMapping("/install-urls") @MigrationWrite
    public Map<String, Object> installUrls(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        return call("Install URLs generated successfully", () -> service.installUrls(safe(body), context(request)));
    }

    @PostMapping("/install-visit") @MigrationWrite
    public Map<String, Object> visit(@RequestBody(required = false) Map<String, Object> body) {
        return call("Install page visit tracked successfully", () -> { service.installVisit(safe(body)); return null; });
    }

    @PostMapping("/referrer") @MigrationWrite
    public Map<String, Object> referrer(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        return call("Referrer data received successfully", () -> service.receiveReferrer(safe(body), context(request)));
    }

    @GetMapping("/status/{clickId}")
    public Map<String, Object> status(@PathVariable String clickId) {
        return call(null, () -> service.status(clickId));
    }

    private TrackingService.ClientContext context(HttpServletRequest request) {
        String forwarded = request.getHeader("x-forwarded-for");
        String ip = forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        Map<String, String> hints = new LinkedHashMap<>();
        for (String name : new String[]{"sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
                "sec-ch-ua-arch", "sec-ch-ua-model"}) hints.put(name, request.getHeader(name));
        return new TrackingService.ClientContext(ip, request.getHeader(HttpHeaders.USER_AGENT),
                request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString()),
                request.getHeader(HttpHeaders.ACCEPT_LANGUAGE), request.getHeader(HttpHeaders.ACCEPT_ENCODING), hints);
    }

    private Map<String, Object> call(String message, Work work) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true); out.put("data", work.run());
            if (message != null) out.put("message", message);
            return out;
        } catch (Exception failure) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", false); out.put("message", failure.getMessage()); return out;
        }
    }
    private Map<String, Object> safe(Map<String, Object> body) { return body == null ? Map.of() : body; }
    @FunctionalInterface private interface Work { Object run(); }
}
