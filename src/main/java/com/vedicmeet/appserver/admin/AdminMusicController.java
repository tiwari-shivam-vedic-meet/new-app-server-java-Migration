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
@RequestMapping("/v2/admin/music")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminMusicController {

    private final AdminMusicService service;

    public AdminMusicController(AdminMusicService service) {
        this.service = service;
    }

    @PostMapping(value = "/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "musicImage", required = false) MultipartFile musicImage) {
        return execute("Music category added successfully", () -> service.addMusicCategory(form, musicImage));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Music category blocked/unblocked successfully", () -> service.blockMusicCategory(body));
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Music category list fetched successfully", () -> service.listMusicCategory(query));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("Music category details fetched successfully", () -> service.getDetailMusicCategory(query));
    }

    @PostMapping(value = "/add_media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addMedia(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "musicImage", required = false) MultipartFile musicImage,
            @RequestPart(value = "musicMedia", required = false) MultipartFile musicMedia) {
        return execute("Music media added successfully", () -> service.addMusicMedia(form, musicImage, musicMedia));
    }

    @PutMapping(value = "/edit_media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editMedia(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "musicImage", required = false) MultipartFile musicImage,
            @RequestPart(value = "musicMedia", required = false) MultipartFile musicMedia) {
        return execute("Music media edited successfully", () -> service.editMusicMedia(form, musicImage, musicMedia));
    }

    @GetMapping("/details_media")
    public ResponseEntity<Map<String, Object>> detailsMedia(@RequestParam Map<String, String> query) {
        return execute("Music media details fetched successfully", () -> service.getDetailMusicMedia(query));
    }

    @PutMapping("/media_block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> mediaBlockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Music media blocked/unblocked successfully", () -> service.blockMusicMedia(body));
    }

    @GetMapping("/media_list")
    public ResponseEntity<Map<String, Object>> mediaList(@RequestParam Map<String, String> query) {
        return execute("Music media list fetched successfully", () -> service.listMusicMedia(query));
    }
}