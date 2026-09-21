package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import java.util.Map;
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

/** Scaffold port of Node rest-apis/modules/admin/count-tracking.js. */
@RestController
@RequestMapping("/v2/admin/count-tracking")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminCountTrackingController {

    private final AdminCountTrackingService service;

    public AdminCountTrackingController(AdminCountTrackingService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(@CurrentUser AuthPrincipal admin) {
        return execute("Dashboard stats retrieved successfully", () -> service.dashboard(admin.getId()));
    }

    @GetMapping("/unread-counts")
    public ResponseEntity<Map<String, Object>> unreadCounts(@CurrentUser AuthPrincipal admin) {
        return execute("Unread counts retrieved successfully", () -> service.unreadCounts(admin.getId()));
    }

    @PostMapping("/track-support/{supportId}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> trackSupport(@CurrentUser AuthPrincipal admin, @PathVariable String supportId) {
        return execute("Support entry tracked successfully", () -> service.trackSupport(admin.getId(), supportId));
    }

    @GetMapping("/entries/{type}")
    public ResponseEntity<Map<String, Object>> entries(@CurrentUser AuthPrincipal admin, @PathVariable String type, @RequestParam Map<String, String> query) {
        return execute("Entries retrieved successfully", () -> service.entries(admin.getId(), type, query));
    }

    @PostMapping("/mark-read")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> markRead(@CurrentUser AuthPrincipal admin, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Entries marked as read successfully", () -> service.markRead(admin.getId(), body));
    }

    @PostMapping("/track-entry")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> trackEntry(@CurrentUser AuthPrincipal admin, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Entry tracked successfully", () -> service.trackEntry(admin.getId(), body));
    }

    @PutMapping("/entry/{entryId}/status")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateStatus(@CurrentUser AuthPrincipal admin, @PathVariable String entryId, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Entry status updated successfully", () -> service.updateStatus(admin.getId(), entryId, body));
    }

    @PutMapping("/entry/{entryId}/priority")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updatePriority(@CurrentUser AuthPrincipal admin, @PathVariable String entryId, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Entry priority updated successfully", () -> service.updatePriority(admin.getId(), entryId, body));
    }

    @PutMapping("/entry/{entryId}/assign")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> assign(@CurrentUser AuthPrincipal admin, @PathVariable String entryId, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Entry assigned successfully", () -> service.assign(admin.getId(), entryId, body));
    }

    @GetMapping("/entry/{entryId}")
    public ResponseEntity<Map<String, Object>> entry(@CurrentUser AuthPrincipal admin, @PathVariable String entryId) {
        return execute("Entry retrieved successfully", () -> service.entry(admin.getId(), entryId));
    }

    @DeleteMapping("/entry/{entryId}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> deleteEntry(@CurrentUser AuthPrincipal admin, @PathVariable String entryId) {
        return execute("Entry deleted successfully", () -> service.deleteEntry(admin.getId(), entryId));
    }

    @GetMapping("/stats/overview")
    public ResponseEntity<Map<String, Object>> stats(@CurrentUser AuthPrincipal admin, @RequestParam Map<String, String> query) {
        return execute("Overview statistics retrieved successfully", () -> service.stats(admin.getId(), query));
    }

}
