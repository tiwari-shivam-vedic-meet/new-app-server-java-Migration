package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.simple;

@RestController
@RequestMapping("/v2/admin/system")
// Node mounts this WITHOUT adminAuthMiddleware.
public class AdminSystemController {

    private final AdminSystemService service;

    public AdminSystemController(AdminSystemService service) {
        this.service = service;
    }

    @PostMapping("/coupon/create")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> createCoupon(@RequestBody(required = false) Map<String, Object> body) {
        return simple(() -> service.createCoupon(body));
    }

    @GetMapping("/coupon/list")
    public ResponseEntity<Map<String, Object>> couponList(@RequestParam Map<String, String> query) {
        return simple(() -> service.couponList(query));
    }

    @GetMapping("/notification")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> notification(@RequestParam Map<String, String> query) {
        return simple(() -> service.notification(query));
    }

    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> availability(@RequestParam Map<String, String> query) {
        return simple(() -> service.availability(query));
    }

    @GetMapping("/master/get")
    public ResponseEntity<Map<String, Object>> masterGet(@RequestParam Map<String, String> query) {
        return simple(() -> service.masterGet(query));
    }

    @PostMapping("/master/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> masterUpdate(@RequestBody(required = false) Map<String, Object> body) {
        return simple(() -> service.masterUpdate(body));
    }
}
