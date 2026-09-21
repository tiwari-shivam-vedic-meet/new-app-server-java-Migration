package com.vedicmeet.appserver.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.simple;

@RestController
@RequestMapping("/v2/admin/cons")
// Node mounts this WITHOUT adminAuthMiddleware.
public class AdminConsController {

    private final AdminConsService service;

    public AdminConsController(AdminConsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return simple(() -> service.list(query));
    }
}
