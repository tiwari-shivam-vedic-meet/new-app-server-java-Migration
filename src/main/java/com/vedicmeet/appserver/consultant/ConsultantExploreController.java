package com.vedicmeet.appserver.consultant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.media.MediaUploadService;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact consultant Explore route family: /explore/add|list|status|delete. */
@RestController
@RequestMapping("/v2/v1/cons/explore")
public class ConsultantExploreController {

    private final ConsultantExploreService service;
    private final AuthUserService users;
    private final MediaUploadService media;
    private final ObjectMapper mapper;

    public ConsultantExploreController(ConsultantExploreService service, AuthUserService users,
                                       MediaUploadService media, ObjectMapper mapper) {
        this.service = service;
        this.users = users;
        this.media = media;
        this.mapper = mapper;
    }

    @PostMapping(value = "/add", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> addJson(@CurrentUser AuthPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        return execute("Explore post added successfully", () -> service.add(
                actor(principal), map(body), List.of(), null, null, null));
    }

    @PostMapping(value = "/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> addMultipart(@CurrentUser AuthPrincipal principal,
                                       @RequestParam Map<String, String> form,
                                       @RequestPart(value = "exploreMedia", required = false) List<MultipartFile> files,
                                       @RequestPart(value = "exploreAudio", required = false) MultipartFile audio,
                                       @RequestPart(value = "videoThumbnail", required = false) MultipartFile thumbnail,
                                       @RequestPart(value = "exploreImage", required = false) MultipartFile exploreImage) {
        return execute("Explore post added successfully", () -> {
            List<String> keys = new ArrayList<>();
            if (files != null) for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) keys.add(media.upload(file, "explore"));
            }
            return service.add(actor(principal), form(form), keys, upload(audio, "music"),
                    upload(thumbnail, "explore"), upload(exploreImage, "explore"));
        });
    }

    @GetMapping("/list")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int limit,
                               @RequestParam(required = false) Boolean all,
                               @RequestParam(required = false) Boolean includeInactive) {
        return execute("Explore posts fetched successfully", () -> service.list(actor(principal), page, limit,
                Boolean.TRUE.equals(all) || Boolean.TRUE.equals(includeInactive)));
    }

    @PatchMapping("/status")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> status(@CurrentUser AuthPrincipal principal,
                                 @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return execute("Explore post status updated successfully", () -> service.setStatus(
                actor(principal), text(input.get("postId")), input.get("status")));
    }

    @DeleteMapping("/delete/{postId}")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> delete(@CurrentUser AuthPrincipal principal, @PathVariable String postId) {
        return execute("Explore post deleted successfully", () -> service.delete(actor(principal), postId));
    }

    private Document actor(AuthPrincipal principal) {
        Document result = users.load(principal);
        if (result == null) throw new IllegalStateException("Invalid token");
        return result;
    }
    private String upload(MultipartFile file, String type) {
        return file == null || file.isEmpty() ? null : media.upload(file, type);
    }
    private Map<String, Object> form(Map<String, String> values) {
        if (values == null) return new LinkedHashMap<>();
        return mapper.convertValue(values, new TypeReference<LinkedHashMap<String, Object>>() {});
    }
    private Map<String, Object> map(Map<String, Object> value) { return value == null ? Collections.emptyMap() : value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private ApiResponse<?> execute(String message, Work work) {
        try { return ApiResponse.ok(message, work.run()); }
        catch (RuntimeException failure) {
            return new ApiResponse<>(false, 500,
                    failure.getMessage() == null ? "Internal server error" : failure.getMessage(), null);
        }
    }
    @FunctionalInterface private interface Work { Object run(); }
}
