package com.vedicmeet.appserver.auth.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request DTOs matching rest-apis/modules/admin/auth.js. */
public final class AdminAuthRequests {

    private AdminAuthRequests() { }

    public static class LoginRequest {
        public String email;
        public String password;
    }

    public static class SignupRequest {
        public String role;
        public String name;
        public String email;
        public String password;
        public Map<String, Object> rights = new LinkedHashMap<>();
    }

    public static class UpdateRequest {
        public String subAdminId;
        public String name;
        public String email;
        public Map<String, Object> rights;
    }

    public static class StatusRequest {
        public String subAdminId;
        public Boolean status;
    }

    public static class ChangePasswordRequest {
        public String oldPassword;
        public String newPassword;
        public String confirmPassword;
    }

    public static class ForgotPasswordRequest {
        public String email;
    }

    public static class ResetPasswordRequest {
        public String token;
        public String password;
        public String confirmPassword;
    }
}
