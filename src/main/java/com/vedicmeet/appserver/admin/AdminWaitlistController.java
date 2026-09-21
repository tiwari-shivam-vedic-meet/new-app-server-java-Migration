package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v2/admin/waitlist")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminWaitlistController {

    private final AdminWaitlistService service;

    public AdminWaitlistController(AdminWaitlistService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> grid(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int pageSize,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filterField,
            @RequestParam(required = false) String filterOperator,
            @RequestParam(required = false) String filterValue) {
        return AdminResponses.simple(() -> service.grid(page, pageSize, sortField, sortDirection,
                filterField, filterOperator, filterValue));
    }

    @PostMapping("/end-waitlist")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> end(@RequestBody Map<String, Object> body) {
        return AdminResponses.simple(() -> service.end(AdminResponses.text(body, "waitlistId")));
    }
}
