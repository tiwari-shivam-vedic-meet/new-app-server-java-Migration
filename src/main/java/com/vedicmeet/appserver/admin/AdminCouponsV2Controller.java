package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
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

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.dataMessage;
import static com.vedicmeet.appserver.admin.AdminResponses.messageOnly;
import static com.vedicmeet.appserver.admin.AdminResponses.simple;

@RestController
@RequestMapping("/v2/admin/v1/coupons-v2")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminCouponsV2Controller {

    private final AdminCouponsV2Service service;

    public AdminCouponsV2Controller(AdminCouponsV2Service service) {
        this.service = service;
    }

    @PostMapping
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> create(@CurrentUser AuthPrincipal admin,
            @RequestBody(required = false) Map<String, Object> body) {
        return dataMessage("Coupon created successfully.", () -> service.create(body, admin == null ? null : admin.getId()));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return simple(() -> service.list(query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> single(@PathVariable String id) {
        return simple(() -> service.single(id));
    }

    @PutMapping("/{id}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return dataMessage("Coupon updated.", () -> service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> deactivate(@PathVariable String id) {
        // FAITHFUL(node-quirk): coupons-v2.js:163 returns {success, message} with no data field.
        return messageOnly("Coupon deactivated.", () -> service.deactivate(id));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody(required = false) Map<String, Object> body) {
        return simple(() -> service.validate(body));
    }
}