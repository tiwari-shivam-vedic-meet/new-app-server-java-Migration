package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.executeData;

@RestController
@RequestMapping("/v2/admin/v1/session")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminSessionBreakdownController {

    private final AdminSessionBreakdownService service;

    public AdminSessionBreakdownController(AdminSessionBreakdownService service) {
        this.service = service;
    }

    @GetMapping("/{id}/breakdown")
    public ResponseEntity<Map<String, Object>> breakdown(@PathVariable String id) {
        return executeData("Session breakdown fetched", () -> service.breakdown(id));
    }

    @GetMapping("/analytics/coupon-offer")
    public ResponseEntity<Map<String, Object>> couponOfferAnalytics(@RequestParam Map<String, String> query) {
        return executeData("Coupon/offer analytics fetched", () -> service.couponOfferAnalytics(query));
    }

    @GetMapping("/analytics/consultant-coupon")
    public ResponseEntity<Map<String, Object>> consultantCouponAnalytics(@RequestParam Map<String, String> query) {
        return executeData("Consultant coupon analytics fetched", () -> service.consultantCouponAnalytics(query));
    }
}