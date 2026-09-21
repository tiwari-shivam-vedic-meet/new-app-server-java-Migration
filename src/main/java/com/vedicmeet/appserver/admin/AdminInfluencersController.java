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
@RequestMapping("/v2/admin/v1/influencers")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminInfluencersController {

    private final AdminInfluencersService service;

    public AdminInfluencersController(AdminInfluencersService service) {
        this.service = service;
    }

    @PostMapping
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> create(@CurrentUser AuthPrincipal admin,
            @RequestBody(required = false) Map<String, Object> body) {
        return dataMessage("Influencer created successfully.", () -> service.create(body, admin == null ? null : admin.getId()));
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
        return dataMessage("Influencer updated.", () -> service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> deactivate(@PathVariable String id) {
        // FAITHFUL(node-quirk): influencers.js:105 returns {success, message} with no data field.
        return messageOnly("Influencer and their coupons deactivated.", () -> service.deactivate(id));
    }

    @GetMapping("/{id}/metrics")
    public ResponseEntity<Map<String, Object>> metrics(@PathVariable String id,
            @RequestParam Map<String, String> query) {
        return simple(() -> service.metrics(id, query));
    }

    @GetMapping("/{id}/ledger")
    public ResponseEntity<Map<String, Object>> ledger(@PathVariable String id,
            @RequestParam Map<String, String> query) {
        return simple(() -> service.ledger(id, query));
    }

    @GetMapping("/{id}/settlements")
    public ResponseEntity<Map<String, Object>> settlements(@PathVariable String id,
            @RequestParam Map<String, String> query) {
        return simple(() -> service.settlements(id, query));
    }

    @GetMapping("/{id}/settlement-preview")
    public ResponseEntity<Map<String, Object>> settlementPreview(@PathVariable String id,
            @RequestParam Map<String, String> query) {
        return simple(() -> service.settlementPreview(id, query));
    }

    @PostMapping("/{id}/settle")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> settle(@CurrentUser AuthPrincipal admin,
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return dataMessage("Settlement created and marked as paid.", () -> service.settle(id, body, admin == null ? null : admin.getId()));
    }
}