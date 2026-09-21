package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/zodiac-message")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminZodiacMessageController {

    private final AdminZodiacMessageService service;

    public AdminZodiacMessageController(AdminZodiacMessageService service) {
        this.service = service;
    }

    @PostMapping("/create")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Zodiac message configuration created successfully", () -> service.create(body, admin.getId()));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Zodiac message configuration updated successfully", () -> service.update(body, admin.getId()));
    }

    @GetMapping("/get")
    public ResponseEntity<Map<String, Object>> get(@RequestParam Map<String, String> query) {
        return execute("Zodiac message configuration fetched successfully", () -> service.get(query));
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Zodiac message configurations fetched successfully", () -> service.list(query));
    }

    @DeleteMapping("/delete")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> delete(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Zodiac message configuration deleted successfully", () -> service.delete(body, admin.getId()));
    }

    @PostMapping("/bulk-update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> bulkUpdate(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Bulk update completed successfully", () -> service.bulkUpdate(body, admin.getId()));
    }

    @GetMapping("/personalized")
    public ResponseEntity<Map<String, Object>> personalized(@RequestParam Map<String, String> query) {
        return execute("Personalized message generated successfully", () -> service.personalized(query));
    }

    @GetMapping("/user-personalized")
    public ResponseEntity<Map<String, Object>> userPersonalized(@RequestParam Map<String, String> query) {
        return execute("User personalized message generated successfully", () -> service.userPersonalized(query));
    }

    @PostMapping("/seed")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> seed(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Zodiac messages seeded successfully", () -> service.seed(body, admin.getId()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestParam Map<String, String> query) {
        return execute("Zodiac message statistics retrieved successfully", () -> service.stats(query));
    }
}
