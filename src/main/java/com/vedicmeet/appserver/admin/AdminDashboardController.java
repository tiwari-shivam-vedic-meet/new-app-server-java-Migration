package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
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
 * Scaffold port of Node rest-apis/modules/admin/dashboard.js (mounted at /dashboard behind
 * adminAuthMiddleware). Routes + success messages mirror Node exactly; aggregation logic lives in
 * {@link AdminDashboardService}, TODO-ported from utils/classes/dashboard.js.
 */
@RestController
@RequestMapping("/v2/admin/dashboard")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details() {
        return execute("Dashboard details fetched successfully", service::getDetails);
    }

    @GetMapping("/graph")
    public ResponseEntity<Map<String, Object>> graph(@RequestParam Map<String, String> query) {
        return execute("Dashboard graph fetched successfully", () -> service.getGraph(query));
    }

    @GetMapping("/free_trail")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> freeTrail() {
        return execute("Free trail enabled successfully", service::enableFreeTrail);
    }

    @GetMapping("/quick-stats")
    public ResponseEntity<Map<String, Object>> quickStats(@RequestParam Map<String, String> query) {
        return execute("Quick stats fetched successfully", () -> service.getQuickStats(query));
    }

    @GetMapping("/last-10-days-data")
    public ResponseEntity<Map<String, Object>> last10Days(@RequestParam Map<String, String> query) {
        return execute("Last 10 days data fetched successfully", () -> service.getLast10DaysData(query));
    }

    @GetMapping("/date-wise-data-percentage-user-management")
    public ResponseEntity<Map<String, Object>> dateWisePercentage(@RequestParam Map<String, String> query) {
        return execute("Date wise data percentage user management fetched successfully",
                () -> service.getDateWiseDataPercentageUserManagement(query));
    }

    @GetMapping("/user-analytics")
    public ResponseEntity<Map<String, Object>> userAnalytics(@RequestParam Map<String, String> query) {
        return execute("User analytics fetched successfully", () -> service.getUserAnalytics(query));
    }

    @GetMapping("/revenue-analytics")
    public ResponseEntity<Map<String, Object>> revenueAnalytics(@RequestParam Map<String, String> query) {
        return execute("Revenue analytics fetched successfully", () -> service.getRevenueAnalytics(query));
    }
}
