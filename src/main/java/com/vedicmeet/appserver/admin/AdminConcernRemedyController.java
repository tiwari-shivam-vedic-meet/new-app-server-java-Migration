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
@RequestMapping("/v2/admin/concern")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminConcernRemedyController {

    private final AdminConcernRemedyService service;

    public AdminConcernRemedyController(AdminConcernRemedyService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listAreaOfConcern(@RequestParam Map<String, String> query) {
        return execute("Concern list fetched successfully", () -> service.listAreaOfConcern(query));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addAreaOfConcern(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Concern added successfully", () -> service.addAreaOfConcern(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateAreaOfConcern(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Concern updated successfully", () -> service.updateAreaOfConcern(body));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblockAreaOfConcern(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Concern blocked/unblocked successfully", () -> service.blockUnblockAreaOfConcern(body));
    }

    @PostMapping("/remedy/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addAreaOfConcernRemedyMedia(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Remedy added successfully", () -> service.addAreaOfConcernRemedyMedia(body));
    }

    @PutMapping("/remedy/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editAreaOfConcernRemedyMedia(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Remedy updated successfully", () -> service.editAreaOfConcernRemedyMedia(body));
    }

    @GetMapping("/remedy/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblockAreaOfConcernRemedyMedia(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Remedy blocked/unblocked successfully",
                () -> service.blockUnblockAreaOfConcernRemedyMedia(body));
    }

    @GetMapping("/remedy")
    public ResponseEntity<Map<String, Object>> listAreaOfConcernRemedyMedia(
            @RequestParam Map<String, String> query) {
        return execute("Remedy list fetched successfully", () -> service.listAreaOfConcernRemedyMedia(query));
    }
}
