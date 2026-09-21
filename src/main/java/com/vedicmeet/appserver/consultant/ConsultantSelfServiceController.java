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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

/** Remaining consultant-owned endpoints from the production /api/v1/cons module. */
@RestController
@RequestMapping("/v2/v1/cons")
public class ConsultantSelfServiceController {

    private final ConsultantSelfService service;
    private final AuthUserService users;
    private final MediaUploadService media;
    private final ObjectMapper mapper;

    public ConsultantSelfServiceController(ConsultantSelfService service, AuthUserService users,
                                           MediaUploadService media, ObjectMapper mapper) {
        this.service = service;
        this.users = users;
        this.media = media;
        this.mapper = mapper;
    }

    @PutMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> updateJson(@CurrentUser AuthPrincipal principal,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant updated successfully",
                () -> service.updateProfile(actor(principal), map(body), null));
    }

    @PutMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> updateMultipart(@CurrentUser AuthPrincipal principal,
                                          @RequestParam Map<String, String> form,
                                          @RequestPart(value = "profileImage", required = false) MultipartFile profileImage) {
        return execute("Consultant updated successfully", () -> service.updateProfile(actor(principal),
                form(form), upload(profileImage, "cons")));
    }

    @PutMapping(value = "/education", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> educationJson(@CurrentUser AuthPrincipal principal,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return execute("Education added/updated successfully",
                () -> service.updateEducation(actor(principal), map(body), List.of()));
    }

    @PutMapping(value = "/education", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> educationMultipart(@CurrentUser AuthPrincipal principal,
                                             @RequestParam Map<String, String> form,
                                             @RequestPart(value = "EducationCertificate", required = false)
                                             List<MultipartFile> certificates) {
        return execute("Education added/updated successfully", () -> {
            List<String> keys = new ArrayList<>();
            if (certificates != null) for (MultipartFile file : certificates) {
                if (file != null && !file.isEmpty()) keys.add(media.upload(file, "education"));
            }
            return service.updateEducation(actor(principal), form(form), keys);
        });
    }

    @PutMapping("/check_mobile_email")
    public ApiResponse<?> checkMobileEmail(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Success", () -> {
            service.checkPhoneAndEmail(map(body));
            return null;
        });
    }

    @GetMapping("/notice_board")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> notices(@CurrentUser AuthPrincipal principal,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int limit) {
        return execute("Notice list fetched", () -> service.notices(actor(principal), page, limit));
    }

    /** Node's read endpoint also appends the consultant to isRead, so it is a gated write. */
    @GetMapping("/notice_board/details")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> noticeDetails(@CurrentUser AuthPrincipal principal,
                                        @RequestParam String noticeBoardId) {
        return execute("Notice details fetched",
                () -> service.noticeDetails(actor(principal), noticeBoardId));
    }

    /** Compatibility with the production GET-with-body contract. */
    @GetMapping("/toggle")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> toggle(@CurrentUser AuthPrincipal principal,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) Object status,
                                 @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        String resolvedType = blank(type) ? text(input.get("type")) : type;
        Object resolvedStatus = status == null ? input.get("status") : status;
        return execute("Emergency call updated",
                () -> service.toggle(actor(principal), resolvedType, resolvedStatus));
    }

    @GetMapping("/warning_list")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> warnings(@CurrentUser AuthPrincipal principal,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int limit) {
        return execute("Warning list", () -> service.warnings(actor(principal), page, limit));
    }

    @GetMapping("/warning_details")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> warningDetails(@CurrentUser AuthPrincipal principal,
                                         @RequestParam(required = false) String warningId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String id = blank(warningId) ? text(map(body).get("warningId")) : warningId;
        return execute("Warning details", () -> service.warningDetails(actor(principal), id));
    }

    @PutMapping("/warning_acknowledge")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> acknowledgeWarning(@CurrentUser AuthPrincipal principal,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return execute("Warning acknowledged", () -> service.acknowledgeWarning(
                actor(principal), text(map(body).get("warningId"))));
    }

    @GetMapping("/pan_bank_details")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> bankOrPan(@CurrentUser AuthPrincipal principal,
                                    @RequestParam String documentType) {
        return execute("Bank details", () -> service.bankOrPanRequest(actor(principal), documentType));
    }

    @PostMapping(value = "/request", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> requestJson(@CurrentUser AuthPrincipal principal,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return execute("Request sent", () -> {
            service.addRequest(actor(principal), map(body), null, null);
            return null;
        });
    }

    @PostMapping(value = "/request", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> requestMultipart(@CurrentUser AuthPrincipal principal,
                                           @RequestParam Map<String, String> form,
                                           @RequestPart(value = "attachmentUrl", required = false) MultipartFile attachment,
                                           @RequestPart(value = "pancardImage", required = false) MultipartFile panImage) {
        return execute("Request sent", () -> {
            service.addRequest(actor(principal), form(form), upload(attachment, "bank"),
                    upload(panImage, "pancard"));
            return null;
        });
    }

    @PostMapping(value = "/event_add", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> eventJson(@CurrentUser AuthPrincipal principal,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return execute("Event added successfully",
                () -> service.addEvent(actor(principal), map(body), null));
    }

    @PostMapping(value = "/event_add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> eventMultipart(@CurrentUser AuthPrincipal principal,
                                         @RequestParam Map<String, String> form,
                                         @RequestPart(value = "eventImage", required = false) MultipartFile image) {
        return execute("Event added successfully", () -> service.addEvent(
                actor(principal), form(form), upload(image, "event")));
    }

    /** Listing also expires stale scheduled events, matching Node, so this GET is gated. */
    @GetMapping("/event_list")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> eventList(@CurrentUser AuthPrincipal principal,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int limit,
                                    @RequestParam(required = false) String search) {
        return execute("Event list fetched", () -> service.listEvents(actor(principal), page, limit, search));
    }

    @GetMapping("/complete_event")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> completeEvents(@CurrentUser AuthPrincipal principal) {
        return execute("Event completed", () -> {
            service.completeEvents(actor(principal));
            return null;
        });
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("Invalid token");
        return actor;
    }
    private String upload(MultipartFile file, String folder) {
        return file == null || file.isEmpty() ? null : media.upload(file, folder);
    }
    private Map<String, Object> form(Map<String, String> values) {
        if (values == null) return new LinkedHashMap<>();
        return mapper.convertValue(values, new TypeReference<LinkedHashMap<String, Object>>() {});
    }
    private Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Collections.emptyMap() : value;
    }
    private ApiResponse<?> execute(String message, Work work) {
        try { return ApiResponse.ok(message, work.run()); }
        catch (RuntimeException failure) {
            return new ApiResponse<>(false, 500,
                    failure.getMessage() == null ? "Internal server error" : failure.getMessage(), null);
        }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    @FunctionalInterface private interface Work { Object run(); }
}
