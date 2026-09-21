package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
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
@RequestMapping("/v2/admin/master")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminMasterController {

    private final AdminMasterService service;

    public AdminMasterController(AdminMasterService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query,
            @CurrentUser AuthPrincipal admin) {
        return execute("Master list fetched successfully", () -> service.list(query, admin.getId()));
    }

    @PostMapping("/add_edit")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addEdit(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Master added/edited successfully", () -> service.addEdit(body, admin.getId()));
    }

    @PostMapping("/add_vimshotri")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addVimshotri(@RequestBody(required = false) Map<String, Object> body,
            @CurrentUser AuthPrincipal admin) {
        return execute("Vimshotri added successfully", () -> service.addVimshotri(body, admin.getId()));
    }

    @GetMapping("/clear")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> clear(@RequestParam Map<String, String> query,
            @CurrentUser AuthPrincipal admin) {
        return execute("Queue cleared successfully", () -> service.clear(query, admin.getId()));
    }

    @GetMapping("/getCategory")
    public ResponseEntity<Map<String, Object>> getCategory(@RequestParam Map<String, String> query,
            @CurrentUser AuthPrincipal admin) {
        return execute("Category list fetched successfully", () -> service.getCategory(query, admin.getId()));
    }
}
