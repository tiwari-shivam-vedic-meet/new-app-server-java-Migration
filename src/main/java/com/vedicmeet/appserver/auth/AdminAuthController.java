package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.dto.AdminAuthRequests;
import com.vedicmeet.appserver.auth.dto.LegacyAuthResponse;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.service.AdminAuthService;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Legacy-compatible admin/sub-admin auth routes with management endpoints secured by role. */
@RestController
@RequestMapping("/v2/admin/auth")
public class AdminAuthController {

    private final AdminAuthService service;
    private final AuthUserService authenticatedUsers;

    public AdminAuthController(AdminAuthService service, AuthUserService authenticatedUsers) {
        this.service = service;
        this.authenticatedUsers = authenticatedUsers;
    }

    @PostMapping("/login")
    public ResponseEntity<LegacyAuthResponse> login(@RequestBody AdminAuthRequests.LoginRequest request) {
        try {
            return ResponseEntity.ok(LegacyAuthResponse.admin(true, 200,
                    "Login successfully", service.login(request), false));
        } catch (AuthException error) {
            if (error.getBodyCode() == 400) {
                return ResponseEntity.ok(LegacyAuthResponse.admin(false, 400,
                        message(error, "Failed to login"), new Document(), true));
            }
            return serverError(error, "Failed to login");
        } catch (Exception error) {
            return serverError(error, "Failed to login");
        }
    }

    @GetMapping("/list")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<LegacyAuthResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) Boolean status) {
        try {
            return success("List fetched successfully", service.list(page, limit, search, status));
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to get list");
        }
    }

    @PostMapping("/sign_up")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> signUp(
            @RequestBody AdminAuthRequests.SignupRequest request,
            @CurrentUser AuthPrincipal caller) {
        try {
            AdminAuthService.SignupResult created = service.signUp(request, caller);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("adminData", created.adminData());
            result.put("subAdminPassword", created.subAdminPassword());
            return success("Admin created successfully", result);
        } catch (AuthException error) {
            if (error.getHttpStatus() == HttpStatus.UNAUTHORIZED) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(LegacyAuthResponse.admin(false, 401,
                                message(error, "Invalid token"), new Document(), null));
            }
            return serverErrorWithResult(error, "Failed to create admin");
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to create admin");
        }
    }

    @PutMapping("/update")
    @MigrationWrite
    @RequireRole(Role.ADMIN)
    public ResponseEntity<LegacyAuthResponse> update(@RequestBody AdminAuthRequests.UpdateRequest request) {
        try {
            return success("Admin updated successfully", service.update(request));
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to update admin");
        }
    }

    @PutMapping("/status_update")
    @MigrationWrite
    @RequireRole(Role.ADMIN)
    public ResponseEntity<LegacyAuthResponse> status(@RequestBody AdminAuthRequests.StatusRequest request) {
        try {
            return success("Admin status updated successfully", service.status(request));
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to update admin status");
        }
    }

    @GetMapping("/details")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<LegacyAuthResponse> details(@RequestParam String subAdminId) {
        try {
            return success("Admin details fetched successfully", service.details(subAdminId));
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to get admin details");
        }
    }

    @PutMapping("/change_password")
    @MigrationWrite
    @RequireRole({Role.ADMIN, Role.SUB_ADMIN})
    public ResponseEntity<LegacyAuthResponse> changePassword(
            @RequestBody AdminAuthRequests.ChangePasswordRequest request,
            @CurrentUser AuthPrincipal principal) {
        try {
            service.changePassword(request, principal, authenticatedUsers.load(principal));
            return success("Admin password changed successfully", null);
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to change admin password");
        }
    }

    @GetMapping("/forgot_password")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> forgotPassword(@RequestParam String email) {
        try {
            AdminAuthRequests.ForgotPasswordRequest request = new AdminAuthRequests.ForgotPasswordRequest();
            request.email = email;
            service.forgotPassword(request);
            return success("Admin forgot password sent successfully", null);
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to send admin forgot password");
        }
    }

    @PutMapping("/reset_password")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> resetPassword(
            @RequestBody AdminAuthRequests.ResetPasswordRequest request) {
        try {
            service.resetPassword(request);
            return success("Admin reset password sent successfully", null);
        } catch (Exception error) {
            return serverErrorWithResult(error, "Failed to reset admin password");
        }
    }

    private ResponseEntity<LegacyAuthResponse> success(String message, Object result) {
        return ResponseEntity.ok(LegacyAuthResponse.admin(true, 200, message, result, null));
    }

    private ResponseEntity<LegacyAuthResponse> serverErrorWithResult(Exception error, String fallback) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(LegacyAuthResponse.admin(false, 500, message(error, fallback), new Document(), null));
    }

    private ResponseEntity<LegacyAuthResponse> serverError(Exception error, String fallback) {
        LegacyAuthResponse response = LegacyAuthResponse.mobile(false, null, message(error, fallback), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private String message(Exception error, String fallback) {
        return error.getMessage() == null || error.getMessage().isBlank() ? fallback : error.getMessage();
    }
}
