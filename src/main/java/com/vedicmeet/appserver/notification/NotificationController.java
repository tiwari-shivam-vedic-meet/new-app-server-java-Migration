package com.vedicmeet.appserver.notification;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import com.vedicmeet.appserver.migration.MigrationWrite;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Port of Node modules/notification.js GET list + GET /count (authed via
 * `authorization` = user OR consultant). The mark_read / mark_all_read routes are
 * writes and out of the read slice. Same success/error envelopes as Node.
 */
@RestController
@RequestMapping("/v2/v1/notification")
public class NotificationController {

    private final NotificationService service;
    private final AuthUserService authUserService;
    private final CryptoService cryptoService;

    public NotificationController(NotificationService service, AuthUserService authUserService,
                                 CryptoService cryptoService) {
        this.service = service;
        this.authUserService = authUserService;
        this.cryptoService = cryptoService;
    }

    @GetMapping
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search) {
        try {
            Document user = authUserService.load(principal);
            return ApiResponse.ok("Notification list fetched successfully",
                    service.listUser(user, page, limit, search));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    @GetMapping("/count")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> count(@CurrentUser AuthPrincipal principal) {
        try {
            Document user = authUserService.load(principal);
            return ApiResponse.ok("Notification count fetched successfully", service.countUser(user));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    // ---- Prompt C writes (diff-pending; enable on /v2 only after harness diff is clean) ----

    /**
     * GET /mark_read — Node validateRequest(NotificationSchema) decrypts reqData; we accept either
     * an encrypted {@code reqData} (preferred, faithful) or a plain {@code notificationId} param.
     */
    @GetMapping("/mark_read")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> markRead(@CurrentUser AuthPrincipal principal,
                                   @RequestParam(required = false) String reqData,
                                   @RequestParam(required = false) String notificationId) {
        try {
            Document user = authUserService.load(principal);
            String id = notificationId;
            if (reqData != null && !reqData.isEmpty()) {
                Map<String, Object> body = cryptoService.decryptToMap(reqData);
                if (body.get("notificationId") != null) id = String.valueOf(body.get("notificationId"));
            }
            return ApiResponse.ok("Notification marked as read successfully", service.markRead(user, id));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /** POST /mark_all_read — no body; marks all of the caller's notifications read. */
    @PostMapping("/mark_all_read")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> markAllRead(@CurrentUser AuthPrincipal principal,
                                      @RequestBody(required = false) Map<String, Object> body) {
        try {
            Document user = authUserService.load(principal);
            return ApiResponse.ok("All notifications marked as read successfully", service.markAllAsRead(user));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
