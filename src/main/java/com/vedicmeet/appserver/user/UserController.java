package com.vedicmeet.appserver.user;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.media.MediaUploadService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import com.vedicmeet.appserver.migration.MigrationWrite;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Map;

/**
 * Port of clean read routes from the Node /user module (modules/user/index.js), which is
 * mounted with userAuthMiddleware (USER only).
 *
 * Currently: GET /user/available_balance. The consultant-discovery reads
 * (consultant_list, consultant_details, cons/get-list, cons/popular) are large,
 * membership-gated, encrypted, pricing/ranking-sensitive endpoints scoped separately —
 * see WEEK3_DISCOVERY_SPEC.md.
 */
@RestController
@RequestMapping("/v2/user")
public class UserController {

    private final UserProfileService service;
    private final AuthUserService authUserService;
    private final CryptoService cryptoService;
    private final MediaUploadService mediaUploadService;

    public UserController(UserProfileService service, AuthUserService authUserService,
                          CryptoService cryptoService, MediaUploadService mediaUploadService) {
        this.service = service;
        this.authUserService = authUserService;
        this.cryptoService = cryptoService;
        this.mediaUploadService = mediaUploadService;
    }

    @GetMapping("/available_balance")
    @RequireRole(Role.USER)
    public ApiResponse<?> availableBalance(@CurrentUser AuthPrincipal principal) {
        try {
            Document user = authUserService.load(principal);
            // Node passes (req.body, req.user) with no userType -> userType is null.
            return ApiResponse.ok("Available balance fetched successfully",
                    service.myAvailableBalance(null, user, null));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /**
     * PUT /update — Prompt C write (diff-pending). Node modules/user/index.js L19 is a multer
     * (multipart/form-data) route: a `reqData` text field (encrypted) plus an optional
     * `profileImage` file. We decrypt reqData via CryptoService and resolve the file via
     * MediaUploadService (a seam until the S3 client is wired). Returns the updated user in the
     * standard envelope.
     */
    @PutMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    @RequireRole(Role.USER)
    public ApiResponse<?> update(@CurrentUser AuthPrincipal principal,
                                 @RequestParam(required = false) String reqData,
                                 @RequestPart(required = false) MultipartFile profileImage) {
        try {
            Document user = authUserService.load(principal);
            Map<String, Object> input = (reqData != null && !reqData.isEmpty())
                    ? cryptoService.decryptToMap(reqData) : new java.util.LinkedHashMap<>();
            String profileImageUrl = (profileImage != null && !profileImage.isEmpty())
                    ? mediaUploadService.upload(profileImage, "user") : null;
            Document updated = service.updateUser(input, user, profileImageUrl);
            return ApiResponse.ok("User updated successfully", updated);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
