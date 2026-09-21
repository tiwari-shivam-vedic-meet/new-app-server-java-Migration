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

/** Native Java port of Node rest-apis/modules/admin/consultant-insights.js. */
@RestController
@RequestMapping("/v2/admin/consultant-insights")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminConsultantInsightsController {

    private final AdminConsultantInsightsService service;

    public AdminConsultantInsightsController(AdminConsultantInsightsService service) {
        this.service = service;
    }

    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> insights(@RequestParam Map<String, String> query) {
        return execute("Consultant insights fetched successfully", () -> service.getConsultantInsights(query));
    }

    @GetMapping("/insights/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam Map<String, String> query) {
        return execute("Consultant insights summary fetched successfully",
                () -> service.getConsultantInsightsSummary(query));
    }
}
