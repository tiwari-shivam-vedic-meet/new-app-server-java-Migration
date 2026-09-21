package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.dto.AdminAuthRequests;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRequestValidatorTest {

    private final AuthRequestValidator validator = new AuthRequestValidator();

    @Test
    void legacyMobileAndCountryCodeAliasesAreCanonicalized() {
        AuthRequests.SendOtpRequest request = new AuthRequests.SendOtpRequest();
        request.userType = "user";
        request.mobile = "9000000001";
        request.countryCode = "91";

        validator.sendOtp(request);

        assertEquals("9000000001", validator.phone(request));
        assertEquals("91", validator.phonePrefix(request));
    }

    @Test
    void conflictingOldAndNewFieldNamesAreRejected() {
        AuthRequests.SendOtpRequest request = new AuthRequests.SendOtpRequest();
        request.phone = "9000000001";
        request.mobile = "9000000002";

        AuthException error = assertThrows(AuthException.class, () -> validator.phone(request));
        assertEquals("phone and mobile do not match", error.getMessage());
    }

    @Test
    void loginRequiresExactlyOneIdentityAndOtpForPhone() {
        AuthRequests.LoginRequest both = validLogin();
        both.email = "user@example.com";
        assertThrows(AuthException.class, () -> validator.login(both));

        AuthRequests.LoginRequest noOtp = validLogin();
        noOtp.otp = null;
        assertThrows(AuthException.class, () -> validator.login(noOtp));

        assertDoesNotThrow(() -> validator.login(validLogin()));
    }

    @Test
    void logoutSingleRequiresTokensButLogoutAllDoesNot() {
        AuthRequests.LogoutRequest request = new AuthRequests.LogoutRequest();
        request.userType = "user";
        request.logOutFrom = "single";
        assertThrows(AuthException.class, () -> validator.logout(request));

        request.logOutFrom = "all";
        assertDoesNotThrow(() -> validator.logout(request));
    }

    @Test
    void adminPasswordRuleMatchesTheNodeJoiContract() {
        AdminAuthRequests.ResetPasswordRequest weak = new AdminAuthRequests.ResetPasswordRequest();
        weak.token = "token";
        weak.password = "password";
        weak.confirmPassword = "password";
        assertThrows(AuthException.class, () -> validator.resetPassword(weak));

        weak.password = "Password@1";
        weak.confirmPassword = "Password@1";
        assertDoesNotThrow(() -> validator.resetPassword(weak));
    }

    private AuthRequests.LoginRequest validLogin() {
        AuthRequests.LoginRequest request = new AuthRequests.LoginRequest();
        request.userType = "user";
        request.mobile = "9000000001";
        request.countryCode = "91";
        request.otp = "1234";
        request.deviceToken = "device-1";
        request.fcmToken = "fcm-1";
        request.deviceType = "android";
        return request;
    }
}
