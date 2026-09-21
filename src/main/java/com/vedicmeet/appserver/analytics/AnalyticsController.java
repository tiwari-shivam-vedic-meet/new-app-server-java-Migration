package com.vedicmeet.appserver.analytics;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Contract-compatible /analytics facade under the migration /v2 prefix. */
@RestController
@RequestMapping("/v2/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @PostMapping("/log-event") @MigrationWrite
    public Map<String, Object> logEvent(@RequestBody(required = false) Map<String, Object> body,
                                        @CurrentUser AuthPrincipal principal, HttpServletRequest request) {
        return call("Event logged successfully", () -> service.logEvent(safe(body), context(principal, request)));
    }

    @PostMapping("/log-registration") @MigrationWrite
    public Map<String, Object> registration(@RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        return call("Registration source logged successfully",
                () -> service.logRegistration(safe(body), context(null, request)));
    }

    @PostMapping("/log-login") @MigrationWrite
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        return call("Login source logged successfully",
                () -> service.logLogin(safe(body), context(null, request)));
    }

    @GetMapping("/summary")
    @RequireRole({Role.USER, Role.CONSULTANT, Role.ADMIN, Role.SUB_ADMIN})
    public Map<String, Object> summary(@RequestParam(required = false, name = "start_date") String start,
                                       @RequestParam(required = false, name = "end_date") String end,
                                       @RequestParam(required = false) String source) {
        return call("Analytics summary retrieved successfully", () -> service.summary(start, end, source));
    }

    @PostMapping("/log-events-batch") @MigrationWrite
    public Map<String, Object> batch(@RequestBody(required = false) Map<String, Object> body,
                                     @CurrentUser AuthPrincipal principal, HttpServletRequest request) {
        return call("Events logged successfully", () -> service.logBatch(safe(body), context(principal, request)));
    }

    @PostMapping("/heartbeat") @MigrationWrite
    public Map<String, Object> heartbeat(@RequestBody(required = false) Map<String, Object> body,
                                         @CurrentUser AuthPrincipal principal, HttpServletRequest request) {
        return call("Heartbeat recorded", () -> service.heartbeat(safe(body), context(principal, request)));
    }

    private AnalyticsService.ClientContext context(AuthPrincipal principal, HttpServletRequest request) {
        String forwarded = request.getHeader("x-forwarded-for");
        String ip = forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return new AnalyticsService.ClientContext(principal == null ? null : principal.getPhone(),
                principal == null ? null : principal.getRole(), ip, request.getHeader("user-agent"),
                request.getHeader("x-device-id"));
    }

    private Map<String, Object> call(String message, Work work) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true); out.put("message", message); out.put("data", work.run());
            return out;
        } catch (Exception failure) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", false);
            if (failure instanceof IllegalArgumentException && failure.getMessage() != null
                    && (failure.getMessage().startsWith("Events array")
                    || failure.getMessage().startsWith("Batch size"))) out.put("code", 400);
            out.put("message", failure.getMessage());
            out.put("data", Map.of());
            return out;
        }
    }
    private Map<String, Object> safe(Map<String, Object> body) { return body == null ? Map.of() : body; }
    @FunctionalInterface private interface Work { Map<String, Object> run(); }
}
