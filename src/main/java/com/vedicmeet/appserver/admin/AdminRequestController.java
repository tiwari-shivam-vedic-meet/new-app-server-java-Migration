package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/request")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminRequestController {

    private final AdminRequestService service;

    public AdminRequestController(AdminRequestService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Request list fetched successfully", () -> service.list(query));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("Request details fetched successfully", () -> service.details(query));
    }

    @PutMapping("/accept_reject")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> acceptAndReject(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Request accepted/rejected successfully", () -> service.acceptAndReject(body));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Request added successfully", () -> service.add(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Request updated successfully", () -> service.update(body));
    }

    @GetMapping("/get-by-consultant-id/{id}")
    public ResponseEntity<Map<String, Object>> getByConsultantId(@PathVariable String id) {
        return execute("Request fetched successfully", () -> service.getByConsultantId(id));
    }
}
