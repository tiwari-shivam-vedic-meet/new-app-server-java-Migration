package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

/** Scaffold port of Node rest-apis/modules/admin/notification-message.js. */
@RestController
@RequestMapping("/v2/admin/notifications-messages")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminNotificationMessageController {

    private final AdminNotificationMessageService service;

    public AdminNotificationMessageController(AdminNotificationMessageService service) {
        this.service = service;
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("", () -> service.list(query));
    }

    @GetMapping("/stats/overview")
    public ResponseEntity<Map<String, Object>> stats() {
        return execute("", () -> service.stats());
    }

    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> categories() {
        return execute("", () -> service.categories());
    }

    @GetMapping("/languages")
    public ResponseEntity<Map<String, Object>> languages() {
        return execute("", () -> service.languages());
    }

    @GetMapping("/target-audiences")
    public ResponseEntity<Map<String, Object>> targetAudiences() {
        return execute("", () -> service.targetAudiences());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return execute("", () -> service.get(id));
    }

    @PostMapping("/")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> create(@CurrentUser AuthPrincipal admin, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification message created successfully", () -> service.create(admin.getId(), body));
    }

    @PutMapping("/{id}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Notification message updated successfully", () -> service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        return execute("Notification message deleted successfully", () -> service.delete(id));
    }

    @PostMapping("/bulk")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> bulk(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Bulk " + value(body, "operation") + " completed successfully", () -> service.bulk(body));
    }

    @PostMapping("/reset-usage")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> resetUsage(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Usage counts reset successfully for " + defaultValue(body, "category", "all") + " messages",
                () -> service.resetUsage(body));
    }

    @PostMapping("/{id}/test")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        return execute("Test notification would be sent", () -> service.test(id));
    }

    @PostMapping("/import")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> importMessages(@CurrentUser AuthPrincipal admin, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Successfully imported " + messageCount(body) + " messages",
                () -> service.importMessages(admin.getId(), body));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam Map<String, String> query) {
        return csv("notification-messages", service.export(query));
    }

    private ResponseEntity<String> csv(String name, String content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + LocalDate.now() + ".csv\"")
                .body(content);
    }

    private String value(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultValue(Map<String, Object> body, String key, String fallback) {
        String value = value(body, key);
        return value.isBlank() ? fallback : value;
    }

    private int messageCount(Map<String, Object> body) {
        Object messages = body == null ? null : body.get("messages");
        return messages instanceof java.util.List<?> list ? list.size() : 0;
    }

}
