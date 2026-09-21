package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/notification")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminNotificationController {

    private final AdminNotificationService service;

    public AdminNotificationController(AdminNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification list fetched successfully", () -> service.list(body));
    }

    @PostMapping("/send")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> send(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification sent successfully", () -> service.send(body));
    }

    @DeleteMapping("/delete")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> delete(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification deleted successfully", () -> service.delete(body));
    }

    @GetMapping("/admin-notification")
    public ResponseEntity<Map<String, Object>> adminNotification(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Admin notification fetched successfully", () -> service.adminNotification(body));
    }

    @GetMapping("/read-notification")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> readNotification(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification read successfully", () -> service.readNotification(body));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification count fetched successfully", () -> service.count(body));
    }

    @GetMapping("/scheduled")
    public ResponseEntity<Map<String, Object>> scheduled(@RequestParam Map<String, String> query) {
        return execute("Scheduled notification fetched successfully", () -> service.scheduled(query));
    }

    @PostMapping("/schedule")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> schedule(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Scheduled notification added successfully", () -> service.schedule(body));
    }

    @DeleteMapping("/scheduled/cancel")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> cancelScheduled(@RequestParam Map<String, String> query) {
        return execute("Scheduled notification cancelled successfully", () -> service.cancelScheduled(query));
    }
}