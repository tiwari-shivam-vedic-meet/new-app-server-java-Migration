package com.vedicmeet.appserver.auth.validation;

import com.vedicmeet.appserver.auth.dto.AdminAuthRequests;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.security.Role;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conditional validation and legacy alias normalization that Bean Validation cannot express cleanly. */
@Component
public class AuthRequestValidator {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9]+(?:[._][A-Za-z0-9]+)*@[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)+$");
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$");

    public String phone(AuthRequests.PhoneAliases request) {
        String canonical = chooseAlias(request.phone, request.mobile, "phone", "mobile");
        if (canonical == null || !canonical.matches("\\d{10}")) throw new AuthException("Invalid phone number");
        return canonical;
    }

    public String phonePrefix(AuthRequests.PhoneAliases request) {
        String canonical = chooseAlias(request.phonePrefix, request.countryCode, "phonePrefix", "countryCode");
        if (canonical == null || canonical.isBlank()) throw new AuthException("Country code and mobile number are required");
        return canonical.trim();
    }

    public String userType(String value) {
        if (!"user".equals(value) && !"cons".equals(value)) throw new AuthException("USER_TYPE_INVALID");
        return value;
    }

    public void sendOtp(AuthRequests.SendOtpRequest request) {
        require(request, "Request body is required");
        userType(request.userType);
        phone(request);
        phonePrefix(request);
        if (request.deviceToken == null) request.deviceToken = ""; // route historically allowed missing validation
    }

    public void verifyOtp(AuthRequests.VerifyOtpRequest request) {
        require(request, "Request body is required");
        phone(request);
        phonePrefix(request);
        if (request.otp == null || request.otp.isBlank()) throw new AuthException("OTP is required");
    }

    public void login(AuthRequests.LoginRequest request) {
        require(request, "Request body is required");
        userType(request.userType);
        boolean emailPresent = request.email != null && !request.email.isBlank();
        boolean phonePresent = (request.phone != null && !request.phone.isBlank())
                || (request.mobile != null && !request.mobile.isBlank());
        if (emailPresent == phonePresent) throw new AuthException("ATLEAST_EMAIL_OR_MOBILE_NEED");
        if (emailPresent) email(request.email);
        if (phonePresent) {
            phone(request);
            phonePrefix(request);
            if (request.otp == null || request.otp.isBlank()) throw new AuthException("OTP_REQUIRED");
        }
        required(request.deviceToken, "deviceToken is required");
        required(request.fcmToken, "fcmToken is required");
        required(request.deviceType, "deviceType is required");
    }

    public void register(AuthRequests.UserRegistrationRequest request) {
        require(request, "Request body is required");
        phone(request);
        phonePrefix(request);
        if (request.email != null && !request.email.isBlank()) email(request.email);
    }

    public void social(AuthRequests.SocialLoginRequest request) {
        require(request, "Request body is required");
        userType(request.userType);
        email(request.email);
    }

    public void logout(AuthRequests.LogoutRequest request) {
        require(request, "Request body is required");
        userType(request.userType);
        if (!"single".equals(request.logOutFrom) && !"all".equals(request.logOutFrom)) {
            throw new AuthException("LOG_OUT_DEVICE_NOT_MATCH");
        }
        if ("single".equals(request.logOutFrom)) {
            required(request.fcmToken, "fcmToken is required");
            required(request.deviceToken, "deviceToken is required");
        }
    }

    public void consultantSignup(AuthRequests.ConsultantSignupRequest request) {
        require(request, "Request body is required");
        email(request.email);
        if (request.name != null && (request.name.length() < 2 || request.name.length() > 50)) {
            throw new AuthException("Name must contain between 2 and 50 characters");
        }
        if ((request.phone != null && !request.phone.isBlank()) || (request.mobile != null && !request.mobile.isBlank())) {
            phone(request);
        }
    }

    public String email(String value) {
        required(value, "email is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalized).matches()) throw new AuthException("Email must be in the format user@example.com");
        return normalized;
    }

    public void adminLogin(AdminAuthRequests.LoginRequest request) {
        require(request, "Request body is required");
        email(request.email);
        required(request.password, "password is required");
    }

    public void adminSignup(AdminAuthRequests.SignupRequest request) {
        require(request, "Request body is required");
        if (!Role.ADMIN.equals(request.role) && !Role.SUB_ADMIN.equals(request.role)) {
            throw new AuthException("role must be admin or sub-admin");
        }
        email(request.email);
        if (Role.ADMIN.equals(request.role)) password(request.password);
        if (request.name != null && (request.name.length() < 2 || request.name.length() > 50)) {
            throw new AuthException("Name must contain between 2 and 50 characters");
        }
    }

    public void adminUpdate(AdminAuthRequests.UpdateRequest request) {
        require(request, "Request body is required");
        objectId(request.subAdminId, "SUBADMIN_ID_REQUIRED");
        if (request.email != null && !request.email.isBlank()) email(request.email);
        if (request.name != null && (request.name.length() < 2 || request.name.length() > 50)) {
            throw new AuthException("Name must contain between 2 and 50 characters");
        }
    }

    public void adminStatus(AdminAuthRequests.StatusRequest request) {
        require(request, "Request body is required");
        objectId(request.subAdminId, "subAdminId is required");
        if (request.status == null) throw new AuthException("status is required");
    }

    public String adminId(String value) {
        return objectId(value, "subAdminId is required");
    }

    public String forgotEmail(AdminAuthRequests.ForgotPasswordRequest request) {
        require(request, "Request is required");
        return email(request.email);
    }

    public void changePassword(AdminAuthRequests.ChangePasswordRequest request) {
        require(request, "Request body is required");
        password(request.oldPassword);
        password(request.newPassword);
        password(request.confirmPassword);
        if (!request.newPassword.equals(request.confirmPassword)) throw new AuthException("PASSWORD_NOT_MATCH");
    }

    public void resetPassword(AdminAuthRequests.ResetPasswordRequest request) {
        require(request, "Request body is required");
        required(request.token, "token is required");
        password(request.password);
        password(request.confirmPassword);
        if (!request.password.equals(request.confirmPassword)) throw new AuthException("PASSWORD_NOT_MATCH");
    }

    public String password(String value) {
        required(value, "password is required");
        if (!PASSWORD.matcher(value).matches()) {
            throw new AuthException("Password must be at least 8 characters and contain an uppercase letter, a number, and a special character");
        }
        return value;
    }

    private String chooseAlias(String first, String second, String firstName, String secondName) {
        String a = trimToNull(first);
        String b = trimToNull(second);
        if (a != null && b != null && !a.equals(b)) {
            throw new AuthException(firstName + " and " + secondName + " do not match");
        }
        return a != null ? a : b;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private void required(String value, String message) {
        if (value == null || value.isBlank()) throw new AuthException(message);
    }

    private String objectId(String value, String message) {
        required(value, message);
        if (!org.bson.types.ObjectId.isValid(value)) throw new AuthException(message);
        return value;
    }

    private void require(Object value, String message) {
        if (value == null) throw new AuthException(message);
    }
}
