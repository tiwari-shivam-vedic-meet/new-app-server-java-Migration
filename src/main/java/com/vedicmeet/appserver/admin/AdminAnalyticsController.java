package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.vedicmeet.appserver.admin.AdminResponses.executeCodeData;

/** Faithful port of Node rest-apis/modules/admin/analytics.js. */
@RestController
@RequestMapping("/v2/admin/analytics")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminAnalyticsController {

    private final AdminAnalyticsService service;

    public AdminAnalyticsController(AdminAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> logs(@RequestParam Map<String, String> query) {
        return executeCodeData("Analytics logs retrieved successfully", () -> service.logs(query));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam Map<String, String> query) {
        return executeCodeData("Analytics summary retrieved successfully", () -> service.summary(query));
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<Map<String, Object>> log(@PathVariable String id) {
        return executeCodeData("Analytics log retrieved successfully", () -> service.log(id));
    }

    @GetMapping("/unique-devices")
    public ResponseEntity<Map<String, Object>> uniqueDevices(@RequestParam Map<String, String> query) {
        return executeCodeData("Unique devices retrieved successfully", () -> service.uniqueDevices(query));
    }

    @GetMapping("/unique-users")
    public ResponseEntity<Map<String, Object>> uniqueUsers(@RequestParam Map<String, String> query) {
        return executeCodeData("Unique users retrieved successfully", () -> service.uniqueUsers(query));
    }

    @GetMapping("/device/{device_id}")
    public ResponseEntity<Map<String, Object>> device(@PathVariable("device_id") String deviceId, @RequestParam Map<String, String> query) {
        return executeCodeData("Device analytics retrieved successfully", () -> service.device(deviceId, query));
    }

    @GetMapping("/user/{user_id}")
    public ResponseEntity<Map<String, Object>> user(@PathVariable("user_id") String userId, @RequestParam Map<String, String> query) {
        return executeCodeData("User analytics retrieved successfully", () -> service.user(userId, query));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam Map<String, String> query) {
        return csv("analytics-logs", service.export(query));
    }

    private ResponseEntity<String> csv(String name, String content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + LocalDate.now() + ".csv\"")
                .body(content);
    }

}
