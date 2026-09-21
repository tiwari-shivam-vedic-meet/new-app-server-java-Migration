package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/v2/admin/vastu")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminVastuCompassController {

    private final AdminVastuCompassService service;

    public AdminVastuCompassController(AdminVastuCompassService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Vastu category list fetched successfully", () -> service.listCategory(query));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Vastu category added successfully", () -> service.addVastuCompassCategory(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Vastu category updated successfully", () -> service.updateVastuCategory(body));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Vastu category blocked/unblocked successfully", () -> service.blockUnblockCategory(body));
    }

    @PostMapping("/zone")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> zone(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Vastu zone added/updated successfully", () -> service.addEditZone(body));
    }

    @GetMapping("/zone/details")
    public ResponseEntity<Map<String, Object>> zoneDetails(@RequestParam Map<String, String> query) {
        return execute("Vastu zone details fetched successfully", () -> service.getZone(query));
    }
}
