package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.dto.LegacyAuthResponse;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.OtpProvider;
import com.vedicmeet.appserver.auth.service.OtpAuthService;
import com.vedicmeet.appserver.auth.service.UserAuthService;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.JwtAuthFilter;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP-compatible port of rest-apis/modules/auths.js under the Java strangler /v2 prefix. */
@RestController
@RequestMapping("/v2/auth")
public class MobileAuthController {

    private final OtpAuthService otp;
    private final UserAuthService users;
    private final AuthUserService authenticatedUsers;

    public MobileAuthController(OtpAuthService otp, UserAuthService users,
                                AuthUserService authenticatedUsers) {
        this.otp = otp;
        this.users = users;
        this.authenticatedUsers = authenticatedUsers;
    }

    @PostMapping("/check-consultation-status-with-this-device")
    public ResponseEntity<LegacyAuthResponse> consultationStatus(
            @RequestBody AuthRequests.DeviceRegistrationRequest request) {
        try {
            long count = users.completedConsultationsForDevice(request.deviceUUID);
            return ok(LegacyAuthResponse.mobile(true, null,
                    "Consultation status checked successfully", count));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, null, message(error, "Failed to upload file"), null));
        }
    }

    @PostMapping("/send-otp")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> sendOtp(@RequestBody AuthRequests.SendOtpRequest request) {
        try {
            OtpProvider.OtpResult result = otp.send(request);
            return ok(LegacyAuthResponse.mobile(true, 200, "OTP sent successfully", result));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Failed to upload file"), null));
        }
    }

    @PostMapping("/resend_otp")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> resendOtp(@RequestBody AuthRequests.SendOtpRequest request) {
        try {
            OtpProvider.OtpResult result = otp.resend(request);
            return ok(LegacyAuthResponse.mobile(true, 200, "OTP sent successfully", result));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Failed to upload file"), null));
        }
    }

    @PostMapping("/cons/verify-otp")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> verifyConsultantOtp(
            @RequestBody AuthRequests.VerifyOtpRequest request) {
        try {
            Map<String, Object> data = users.verifyConsultantOtp(request);
            return ok(LegacyAuthResponse.mobile(true, 200, "OTP verified successfully",
                    data.isEmpty() ? null : data));
        } catch (AuthException error) {
            HttpStatus status = error.getHttpStatus() == HttpStatus.BAD_REQUEST
                    ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
            int code = status == HttpStatus.BAD_REQUEST ? 400 : 500;
            return status(status, LegacyAuthResponse.mobile(false, code,
                    message(error, status == HttpStatus.BAD_REQUEST ? "Invalid OTP" : "Failed to upload file"), null));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Failed to upload file"), null));
        }
    }

    @PostMapping("/user/verify-otp")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> verifyUserOtp(@RequestBody AuthRequests.VerifyOtpRequest request) {
        try {
            return ok(LegacyAuthResponse.mobile(true, null, "OTP verified successfully",
                    users.verifyUserOtp(request)));
        } catch (Exception error) {
            // Node intentionally returns HTTP 200 and omits `code` for this endpoint's failures.
            return ok(LegacyAuthResponse.mobile(false, null, message(error, "Failed to upload file"), null));
        }
    }

    @PostMapping("/user/register")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> registerUser(
            @RequestBody AuthRequests.UserRegistrationRequest request,
            @RequestHeader(value = "platform-type", defaultValue = "android") String platform) {
        try {
            UserAuthService.RegistrationResult registered = users.registerUser(request, platform);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", registered.token());
            data.put("user", registered.user());
            if (!registered.existing()) {
                Map<String, Object> navigation = new LinkedHashMap<>();
                navigation.put("isActive", true);
                navigation.put("consultantID", null);
                data.put("navigateToConsultant", navigation);
            }
            return ok(LegacyAuthResponse.mobile(true, 200,
                    registered.existing() ? "User already exists" : "User registered successfully", data));
        } catch (Exception error) {
            return ok(LegacyAuthResponse.mobile(false, null, message(error, "Failed to upload file"), null));
        }
    }

    @GetMapping("/verify")
    public ResponseEntity<LegacyAuthResponse> verify(
            @CurrentUser AuthPrincipal principal,
            HttpServletRequest request,
            @RequestHeader(value = "platform-type", defaultValue = "android") String platform,
            @RequestHeader(value = "vm-user-type", required = false) String userType) {
        Document appDetails = users.authAppDetails(platform, userType);
        if (principal == null) {
            if ("invalid".equals(request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR))) {
                return status(HttpStatus.INTERNAL_SERVER_ERROR,
                        LegacyAuthResponse.mobile(false, null, "Failed to fetch user details", null));
            }
            return ok(LegacyAuthResponse.mobile(false, null, "Authorization token required",
                    Map.of("appDetails", appDetails)));
        }
        try {
            return ok(LegacyAuthResponse.mobile(true, null, null,
                    users.verifyCurrent(principal, platform, userType)));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, null, "Failed to fetch user details", null));
        }
    }

    @GetMapping("/details")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ResponseEntity<LegacyAuthResponse> details(
            @RequestParam String userType,
            @CurrentUser AuthPrincipal principal) {
        try {
            Document account = authenticatedUsers.load(principal);
            return ok(LegacyAuthResponse.mobile(true, null, "Get details",
                    users.details(userType, principal, account)));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, null, message(error, "Something went wrong"), null));
        }
    }

    @PostMapping("/login")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> login(@RequestBody AuthRequests.LoginRequest request) {
        try {
            return ok(LegacyAuthResponse.mobile(true, 200, "Login successfully", users.login(request)));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Something went wrong"), null));
        }
    }

    @PostMapping("/social-login")
    @MigrationWrite
    public ResponseEntity<LegacyAuthResponse> socialLogin(@RequestBody AuthRequests.SocialLoginRequest request) {
        try {
            return ok(LegacyAuthResponse.mobile(true, 200, "Social login successfully", users.socialLogin(request)));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Something went wrong"), null));
        }
    }

    @DeleteMapping("/delete")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ResponseEntity<LegacyAuthResponse> delete(
            @RequestParam String userType,
            @CurrentUser AuthPrincipal principal) {
        try {
            Document account = authenticatedUsers.load(principal);
            return ok(LegacyAuthResponse.mobile(true, 200, "Account deleted successfully",
                    users.deleteAccount(userType, principal, account)));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Something went wrong"), null));
        }
    }

    @PutMapping("/log_out")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ResponseEntity<LegacyAuthResponse> logout(
            @RequestBody AuthRequests.LogoutRequest request,
            @CurrentUser AuthPrincipal principal) {
        try {
            users.logout(request, principal, authenticatedUsers.load(principal));
            return ok(LegacyAuthResponse.mobile(true, 200, "Logout successfully", null));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Something went wrong"), null));
        }
    }

    /** Additive endpoint for clients that rotate FCM/device credentials without a fresh login. */
    @PostMapping("/device/register")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ResponseEntity<LegacyAuthResponse> registerDevice(
            @RequestBody AuthRequests.DeviceRegistrationRequest request,
            @CurrentUser AuthPrincipal principal) {
        try {
            Document updated = users.registerDevice(request, principal, authenticatedUsers.load(principal));
            return ok(LegacyAuthResponse.mobile(true, 200, "Device registered successfully", updated));
        } catch (Exception error) {
            return ok(LegacyAuthResponse.mobile(false, 500, message(error, "Something went wrong"), null));
        }
    }

    @GetMapping("/version")
    public ResponseEntity<LegacyAuthResponse> version(@RequestParam String userType,
                                                       @RequestParam String deviceType) {
        try {
            return ok(LegacyAuthResponse.mobile(true, 200, "App version fetched successfully",
                    users.appVersion(userType, deviceType)));
        } catch (Exception error) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR,
                    LegacyAuthResponse.mobile(false, 500, message(error, "Something went wrong"), null));
        }
    }

    private ResponseEntity<LegacyAuthResponse> ok(LegacyAuthResponse body) {
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<LegacyAuthResponse> status(HttpStatus status, LegacyAuthResponse body) {
        return ResponseEntity.status(status).body(body);
    }

    private String message(Exception error, String fallback) {
        return error.getMessage() == null || error.getMessage().isBlank() ? fallback : error.getMessage();
    }
}
