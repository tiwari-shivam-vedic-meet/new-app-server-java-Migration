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
 * Scaffold port of Node rest-apis/modules/admin/enhanced-dashboard.js (mounted at
 * /enhanced-dashboard behind adminAuthMiddleware). Logic lives in
 * {@link AdminEnhancedDashboardService}, TODO-ported from utils/classes/enhanced-dashboard.js.
 */
@RestController
@RequestMapping("/v2/admin/enhanced-dashboard")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminEnhancedDashboardController {

    private final AdminEnhancedDashboardService service;

    public AdminEnhancedDashboardController(AdminEnhancedDashboardService service) {
        this.service = service;
    }

    @GetMapping("/revenue-analytics")
    public ResponseEntity<Map<String, Object>> revenue(@RequestParam Map<String, String> query) {
        return execute("Revenue analytics fetched successfully", () -> service.getRevenueAnalytics(query));
    }

    @GetMapping("/user-analytics")
    public ResponseEntity<Map<String, Object>> user(@RequestParam Map<String, String> query) {
        return execute("User analytics fetched successfully", () -> service.getUserAnalytics(query));
    }

    @GetMapping("/consultant-performance")
    public ResponseEntity<Map<String, Object>> consultantPerformance(@RequestParam Map<String, String> query) {
        return execute("Consultant performance data fetched successfully",
                () -> service.getConsultantPerformance(query));
    }

    @GetMapping("/realtime-analytics")
    public ResponseEntity<Map<String, Object>> realtime() {
        return execute("Real-time analytics fetched successfully", service::getRealtimeAnalytics);
    }

    @GetMapping("/service-distribution")
    public ResponseEntity<Map<String, Object>> serviceDistribution(@RequestParam Map<String, String> query) {
        return execute("Service distribution data fetched successfully",
                () -> service.getServiceDistribution(query));
    }

    @GetMapping("/user-growth-trends")
    public ResponseEntity<Map<String, Object>> userGrowth(@RequestParam Map<String, String> query) {
        return execute("User growth trends fetched successfully", () -> service.getUserGrowthTrends(query));
    }

    @GetMapping("/consultant-earnings")
    public ResponseEntity<Map<String, Object>> consultantEarnings(@RequestParam Map<String, String> query) {
        return execute("Consultant earnings data fetched successfully",
                () -> service.getConsultantEarnings(query));
    }

    @GetMapping("/session-analytics")
    public ResponseEntity<Map<String, Object>> session(@RequestParam Map<String, String> query) {
        return execute("Session analytics fetched successfully", () -> service.getSessionAnalytics(query));
    }

    @GetMapping("/support-analytics")
    public ResponseEntity<Map<String, Object>> support(@RequestParam Map<String, String> query) {
        return execute("Support analytics fetched successfully", () -> service.getSupportAnalytics(query));
    }
}
