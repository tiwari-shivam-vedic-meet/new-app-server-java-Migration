package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthRouteSafetyTest {

    @Test
    void allMigratedAuthWritesRemainBehindTheMigrationKillSwitch() {
        assertAnnotated(MobileAuthController.class, MigrationWrite.class,
                "sendOtp", "resendOtp", "verifyConsultantOtp", "verifyUserOtp", "registerUser",
                "login", "socialLogin", "delete", "logout", "registerDevice");
        assertAnnotated(ConsultantAuthController.class, MigrationWrite.class,
                "signUpJson", "signUpMultipart", "approve");
        assertAnnotated(AdminAuthController.class, MigrationWrite.class,
                "signUp", "update", "status", "changePassword", "forgotPassword", "resetPassword");
    }

    @Test
    void accountAndAdminManagementRoutesRequireExplicitRoles() {
        assertAnnotated(MobileAuthController.class, RequireRole.class,
                "details", "delete", "logout", "registerDevice");
        assertAnnotated(ConsultantAuthController.class, RequireRole.class, "approve");
        assertAnnotated(AdminAuthController.class, RequireRole.class,
                "list", "update", "status", "details", "changePassword");
    }

    @Test
    void consultationStatusRemainsPublicAndReadOnlyLikeNode() throws Exception {
        Method method = MobileAuthController.class.getDeclaredMethod(
                "consultationStatus",
                com.vedicmeet.appserver.auth.dto.AuthRequests.DeviceRegistrationRequest.class);
        assertNull(method.getAnnotation(MigrationWrite.class));
        assertNull(method.getAnnotation(RequireRole.class));
    }

    private void assertAnnotated(Class<?> type, Class<? extends Annotation> annotation,
                                 String... methodNames) {
        List<Method> methods = List.of(type.getDeclaredMethods());
        for (String name : methodNames) {
            Method method = methods.stream().filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElseThrow(() -> new AssertionError(type.getSimpleName() + "." + name));
            assertNotNull(method.getAnnotation(annotation),
                    () -> type.getSimpleName() + "." + name + " must have @" + annotation.getSimpleName());
        }
    }
}
