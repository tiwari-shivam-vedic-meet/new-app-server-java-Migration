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

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/event")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminEventController {

    private final AdminEventService service;

    public AdminEventController(AdminEventService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Event list fetched successfully", () -> service.list(query));
    }

    @PostMapping("/send_message")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> sendMessages(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Event message sent successfully", () -> service.sendMessages(body));
    }
}
