package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.dataMessage;
import static com.vedicmeet.appserver.admin.AdminResponses.simple;

@RestController
@RequestMapping("/v2/admin/flag")
// Node mounts this WITHOUT adminAuthMiddleware.
public class AdminFlagController {

    private final AdminFlagService service;

    public AdminFlagController(AdminFlagService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return simple(() -> service.list(query));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        return simple(() -> service.update(body));
    }

    @PutMapping("/refund_request")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> refundRequest(@RequestBody(required = false) Map<String, Object> body) {
        return simple(() -> service.refundRequest(body));
    }

    @GetMapping("/get-flags")
    public ResponseEntity<Map<String, Object>> getFlags(@RequestParam Map<String, String> query) {
        return dataMessage("Successfully fetched flags!", () -> service.getFlags(query));
    }
}
