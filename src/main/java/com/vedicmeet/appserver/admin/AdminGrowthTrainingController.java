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
@RequestMapping("/v2/admin/growth")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminGrowthTrainingController {

    private final AdminGrowthTrainingService service;

    public AdminGrowthTrainingController(AdminGrowthTrainingService service) {
        this.service = service;
    }

    @PostMapping("/category_add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addCategory(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Growth training category added successfully", () -> service.addGrowthTrainingCategory(body));
    }

    @PutMapping("/category_update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateCategory(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Growth training category updated successfully", () -> service.updateGrowthTrainingCategory(body));
    }

    @PutMapping("/category_block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblockCategory(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Growth training category blocked successfully", () -> service.blockUnblockCategory(body));
    }

    @GetMapping("/category_list")
    public ResponseEntity<Map<String, Object>> categoryList(@RequestParam Map<String, String> query) {
        return execute("Growth training category list fetched successfully", () -> service.listMedidationCategory(query));
    }

    @GetMapping("/category_details")
    public ResponseEntity<Map<String, Object>> categoryDetails(@RequestParam Map<String, String> query) {
        return execute("Growth training category details fetched successfully", () -> service.getDetailsCategory(query));
    }

    @PostMapping("/media_add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addMedia(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Growth training media added successfully", () -> service.addMedia(body));
    }

    @PutMapping("/media_update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateMedia(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Growth training media updated successfully", () -> service.editMedia(body));
    }

    @PutMapping("/media_block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblockMedia(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Growth training media blocked successfully", () -> service.blockUnblockMedia(body));
    }

    @GetMapping("/media_details")
    public ResponseEntity<Map<String, Object>> mediaDetails(@RequestParam Map<String, String> query) {
        return execute("Growth training media details fetched successfully", () -> service.detailMedia(query));
    }

    @GetMapping("/media_list")
    public ResponseEntity<Map<String, Object>> mediaList(@RequestParam Map<String, String> query) {
        return execute("Growth training media list fetched successfully", () -> service.listMedia(query));
    }
}