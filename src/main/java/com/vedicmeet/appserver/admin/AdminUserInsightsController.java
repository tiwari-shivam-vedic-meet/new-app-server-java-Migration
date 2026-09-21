package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

/**
 * Native port of Node rest-apis/modules/admin/user-insights.js (mounted behind
 * adminAuthMiddleware). Routes + response envelopes mirror Node exactly.
 */
@RestController
@RequestMapping("/v2/admin/user-insights")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminUserInsightsController {

    private final AdminUserInsightsService service;

    public AdminUserInsightsController(AdminUserInsightsService service) {
        this.service = service;
    }

    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> insights(@RequestParam Map<String, String> query) {
        return execute("User insights fetched successfully", () -> service.getUserInsights(query));
    }

    @GetMapping("/insights/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam Map<String, String> query) {
        return execute("User insights summary fetched successfully", () -> service.getUserInsightsSummary(query));
    }

    @GetMapping("/users/one-consultation")
    public ResponseEntity<Map<String, Object>> oneConsultation(@RequestParam Map<String, String> query) {
        return execute("Users with one completed consultation fetched successfully",
                () -> service.getUsersWithOneCompletedConsultation(query));
    }
}
