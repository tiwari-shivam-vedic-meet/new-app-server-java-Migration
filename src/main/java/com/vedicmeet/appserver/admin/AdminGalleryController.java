package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/gallery")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminGalleryController {

    private final AdminGalleryService service;

    public AdminGalleryController(AdminGalleryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Gallery list fetched successfully", () -> service.listGallery(query));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("Gallery list fetched successfully", () -> service.getConsultantGallery(query));
    }

    @PutMapping("/approve")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> approve(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Gallery images approved successfully", () -> service.approveGalleryImages(body));
    }

    @DeleteMapping("/delete")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> delete(@RequestParam Map<String, String> query) {
        return execute("Gallery image deleted successfully", () -> service.deleteImage(query));
    }

    @PostMapping(value = "/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(
            @RequestParam String consultantId, @RequestParam String galleryType,
            @RequestPart(value = "images", required = false) MultipartFile[] images) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consultantId", consultantId);
        body.put("galleryType", galleryType);
        return execute("Gallery added successfully",
                () -> service.addGallery(body, images == null ? null : Arrays.asList(images)));
    }
}
