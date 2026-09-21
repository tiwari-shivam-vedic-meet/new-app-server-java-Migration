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
@RequestMapping("/v2/admin/cms")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminCmsController {

    private final AdminCmsService service;

    public AdminCmsController(AdminCmsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("CMS fetched successfully", () -> service.getCmsDetails(query));
    }

    @PostMapping("/add_update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addUpdate(@RequestBody(required = false) Map<String, Object> body) {
        return execute("CMS added successfully", () -> service.addAndUpdateCms(body));
    }

    @PostMapping("/add_content")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addContent(@RequestBody(required = false) Map<String, Object> body) {
        return execute("CMS added successfully", () -> service.addContent(body));
    }

    @PutMapping("/edit_content")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editContent(@RequestBody(required = false) Map<String, Object> body) {
        return execute("CMS updated successfully", () -> service.editContent(body));
    }

    @GetMapping("/list_content")
    public ResponseEntity<Map<String, Object>> listContent(@RequestParam Map<String, String> query) {
        return execute("CMS list fetched successfully", () -> service.listContent(query));
    }

    @PutMapping("/block_delete")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockDelete(@RequestBody(required = false) Map<String, Object> body) {
        return execute("CMS blocked/deleted successfully", () -> service.statusChangeContent(body));
    }
}
