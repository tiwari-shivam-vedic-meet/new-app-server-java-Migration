package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/category")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminCategoryController {

    private final AdminCategoryService service;

    public AdminCategoryController(AdminCategoryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Category list fetched successfully", () -> service.listCategory(query));
    }

    @PostMapping(value = "/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "categoryImage", required = false) MultipartFile categoryImage) {
        return execute("Category added successfully", () -> service.addCategory(form, categoryImage));
    }

    @PutMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "categoryImage", required = false) MultipartFile categoryImage) {
        return execute("Category updated successfully", () -> service.updateCategory(form, categoryImage));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Category blocked/unblocked successfully", () -> service.blockUnblock(body));
    }

    @PostMapping(value = "/add_media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addMedia(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "categoryMedia", required = false) MultipartFile categoryMedia) {
        return execute("Category music/mantra added successfully", () -> service.addMusicOrMantra(form, categoryMedia));
    }

    @PutMapping(value = "/update_media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateMedia(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "categoryMedia", required = false) MultipartFile categoryMedia) {
        return execute("Category music/mantra updated successfully", () -> service.updateMusicOrMantra(form, categoryMedia));
    }

    @GetMapping("/media_list")
    public ResponseEntity<Map<String, Object>> mediaList(@RequestParam Map<String, String> query) {
        return execute("Category music/mantra list fetched successfully", () -> service.listMediaCategory(query));
    }
}
