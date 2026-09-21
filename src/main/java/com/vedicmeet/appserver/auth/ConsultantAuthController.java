package com.vedicmeet.appserver.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.dto.LegacyAuthResponse;
import com.vedicmeet.appserver.auth.service.ConsultantRegistrationService;
import com.vedicmeet.appserver.media.MediaUploadService;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Consultant signup and admin approval routes from the Node consultant modules. */
@RestController
public class ConsultantAuthController {

    private final ConsultantRegistrationService service;
    private final MediaUploadService media;
    private final ObjectMapper mapper;

    public ConsultantAuthController(ConsultantRegistrationService service, MediaUploadService media,
                                    ObjectMapper mapper) {
        this.service = service;
        this.media = media;
        this.mapper = mapper;
    }

    @PostMapping(value = "/v2/v1/cons/signUp", consumes = MediaType.APPLICATION_JSON_VALUE)
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> signUpJson(
            @RequestBody AuthRequests.ConsultantSignupRequest request) {
        return signUp(request, null);
    }

    @PostMapping(value = "/v2/v1/cons/signUp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> signUpMultipart(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "profileImage", required = false) MultipartFile profileImage) {
        AuthRequests.ConsultantSignupRequest request = mapper.convertValue(
                form, AuthRequests.ConsultantSignupRequest.class);
        return signUp(request, profileImage);
    }

    @PutMapping("/v2/admin/consultant/approve")
    @MigrationWrite
    @RequireRole(Role.ADMIN)
    public ResponseEntity<LegacyAuthResponse> approve(
            @RequestBody AuthRequests.ConsultantApprovalRequest request) {
        try {
            service.approve(request.consultantId, request.approve);
            return ResponseEntity.ok(LegacyAuthResponse.admin(true, 200,
                    "Consultant approved successfully", null, null));
        } catch (Exception error) {
            return ResponseEntity.ok(LegacyAuthResponse.admin(false, 500,
                    message(error), new Document(), null));
        }
    }

    private ResponseEntity<LegacyAuthResponse> signUp(
            AuthRequests.ConsultantSignupRequest request, MultipartFile profileImage) {
        try {
            String key = profileImage == null || profileImage.isEmpty()
                    ? null : media.upload(profileImage, "cons");
            Document result = service.signUp(request, key);
            return ResponseEntity.ok(LegacyAuthResponse.admin(true, 200,
                    "Consultant signed up successfully", result, null));
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(LegacyAuthResponse.admin(false, 500, message(error), null, null));
        }
    }

    private String message(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "Something went wrong" : error.getMessage();
    }
}
