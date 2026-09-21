package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.dto.AdminAuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.PasswordResetMailProvider;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.service.AdminAuthService;
import com.vedicmeet.appserver.auth.service.AuthTokenFactory;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.JwtService;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.security.TokenRevocationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthServiceTest {

    @Test
    void loginUsesBcryptAndReturnsNoPassword() {
        Fixture f = fixture(false);
        Document admin = admin(Role.ADMIN, "Admin@123");
        when(f.repository.findAdminByEmail("admin@example.com")).thenReturn(admin);
        AdminAuthRequests.LoginRequest request = new AdminAuthRequests.LoginRequest();
        request.email = "ADMIN@example.com";
        request.password = "Admin@123";

        Map<String, Object> result = f.service.login(request);

        assertEquals("admin@example.com", result.get("email"));
        assertNotNull(result.get("token"));
        assertFalse(result.containsKey("password"));
    }

    @Test
    void invalidPasswordKeepsLegacyHttp200Body400Decision() {
        Fixture f = fixture(false);
        when(f.repository.findAdminByEmail("admin@example.com"))
                .thenReturn(admin(Role.ADMIN, "Admin@123"));
        AdminAuthRequests.LoginRequest request = new AdminAuthRequests.LoginRequest();
        request.email = "admin@example.com";
        request.password = "Wrong@123";

        AuthException error = assertThrows(AuthException.class, () -> f.service.login(request));

        assertEquals(HttpStatus.OK, error.getHttpStatus());
        assertEquals(400, error.getBodyCode());
    }

    @Test
    void authenticatedAdminCanCreateSubAdminWithHashedGeneratedPassword() {
        Fixture f = fixture(false);
        ObjectId callerId = new ObjectId();
        AuthPrincipal caller = new AuthPrincipal(Map.of(
                "role", Role.ADMIN, "_id", callerId.toHexString(),
                "jti", "caller-jti", "tokenVersion", 0));
        when(f.repository.findAdminById(callerId.toHexString()))
                .thenReturn(new Document("_id", callerId).append("role", Role.ADMIN)
                        .append("status", true).append("authTokenVersion", 0));
        when(f.repository.insertAdmin(any())).thenAnswer(invocation -> {
            Document created = invocation.getArgument(0);
            created.put("_id", new ObjectId());
            return created;
        });
        AdminAuthRequests.SignupRequest request = new AdminAuthRequests.SignupRequest();
        request.role = Role.SUB_ADMIN;
        request.name = "Operations";
        request.email = "OPS@example.com";

        AdminAuthService.SignupResult result = f.service.signUp(request, caller);

        assertTrue(result.subAdminPassword().startsWith("Vedic@"));
        assertFalse(result.adminData().containsKey("password"));
        ArgumentCaptor<Document> created = ArgumentCaptor.forClass(Document.class);
        verify(f.repository).insertAdmin(created.capture());
        assertTrue(f.passwords.matches(result.subAdminPassword(), created.getValue().getString("password")));
    }

    @Test
    void publicAdminBootstrapIsClosedByDefault() {
        Fixture f = fixture(false);
        AdminAuthRequests.SignupRequest request = new AdminAuthRequests.SignupRequest();
        request.role = Role.ADMIN;
        request.name = "Admin";
        request.email = "admin@example.com";
        request.password = "Admin@123";

        AuthException error = assertThrows(AuthException.class, () -> f.service.signUp(request, null));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getHttpStatus());
    }

    @Test
    void forgotPasswordStoresOnlyNonceHashAndSendsShortLivedResetToken() {
        Fixture f = fixture(false);
        Document admin = admin(Role.ADMIN, "Admin@123");
        when(f.repository.findAdminByEmail("admin@example.com")).thenReturn(admin);
        AdminAuthRequests.ForgotPasswordRequest request = new AdminAuthRequests.ForgotPasswordRequest();
        request.email = "ADMIN@example.com";

        f.service.forgotPassword(request);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Date> expiry = ArgumentCaptor.forClass(Date.class);
        verify(f.repository).savePasswordReset(any(ObjectId.class), hash.capture(), expiry.capture());
        assertFalse(hash.getValue().isBlank());
        assertTrue(expiry.getValue().after(new Date()));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(f.mail).send(anyString(), anyString(), token.capture());
        assertEquals("password_reset", f.jwt.verify(token.getValue()).get("purpose"));
    }

    @Test
    void passwordResetConsumesNonceOnceAndInvalidatesExistingSessions() {
        Fixture f = fixture(false);
        Document before = admin(Role.ADMIN, "Admin@123");
        String nonce = "single-use-nonce";
        String token = f.tokens.passwordResetToken(before, nonce);
        Document updated = new Document(before).append("authTokenVersion", 1);
        when(f.repository.findAdminById(before.getObjectId("_id").toHexString())).thenReturn(before);
        when(f.repository.consumePasswordReset(any(ObjectId.class), anyString(), anyString()))
                .thenReturn(updated, null);
        AdminAuthRequests.ResetPasswordRequest request = new AdminAuthRequests.ResetPasswordRequest();
        request.token = token;
        request.password = "Changed@123";
        request.confirmPassword = "Changed@123";

        f.service.resetPassword(request);
        assertThrows(AuthException.class, () -> f.service.resetPassword(request));

        verify(f.cache).invalidate(Role.ADMIN, before);
        verify(f.cache).invalidate(Role.ADMIN, updated);
    }

    @Test
    void passwordResetWithMissingAccountEmailFailsCleanlyInsteadOfNullPointer() {
        Fixture f = fixture(false);
        Document tokenAdmin = admin(Role.ADMIN, "Admin@123");
        String token = f.tokens.passwordResetToken(tokenAdmin, "nonce");
        when(f.repository.findAdminById(tokenAdmin.getObjectId("_id").toHexString()))
                .thenReturn(new Document("_id", tokenAdmin.getObjectId("_id")));
        AdminAuthRequests.ResetPasswordRequest request = new AdminAuthRequests.ResetPasswordRequest();
        request.token = token;
        request.password = "Changed@123";
        request.confirmPassword = "Changed@123";

        AuthException error = assertThrows(AuthException.class, () -> f.service.resetPassword(request));

        assertEquals("Invalid or expired reset token", error.getMessage());
    }

    @Test
    void changePasswordChecksOldHashThenRevokesCurrentToken() {
        Fixture f = fixture(false);
        Document account = admin(Role.SUB_ADMIN, "OldPass@1");
        Document updated = new Document(account).append("authTokenVersion", 1);
        when(f.repository.updateAdminPassword(any(ObjectId.class), anyString(), any(Boolean.class)))
                .thenReturn(updated);
        AuthPrincipal principal = new AuthPrincipal(Map.of(
                "role", Role.SUB_ADMIN, "_id", account.getObjectId("_id").toHexString(),
                "jti", "sub-jti", "tokenVersion", 0));
        AdminAuthRequests.ChangePasswordRequest request = new AdminAuthRequests.ChangePasswordRequest();
        request.oldPassword = "OldPass@1";
        request.newPassword = "NewPass@2";
        request.confirmPassword = "NewPass@2";

        f.service.changePassword(request, principal, account);

        verify(f.revocations).revoke(principal);
        verify(f.cache).invalidate(Role.SUB_ADMIN, account);
    }

    private Fixture fixture(boolean bootstrap) {
        AuthRepository repository = mock(AuthRepository.class);
        PasswordEncoder passwords = new BCryptPasswordEncoder(4);
        JwtService jwt = new JwtService("authentication-test-secret");
        AuthTokenFactory tokens = new AuthTokenFactory(jwt, 900);
        PasswordResetMailProvider mail = mock(PasswordResetMailProvider.class);
        AuthDocumentCache cache = mock(AuthDocumentCache.class);
        TokenRevocationService revocations = mock(TokenRevocationService.class);
        AdminAuthService service = new AdminAuthService(repository, new AuthRequestValidator(),
                passwords, tokens, jwt, mail, cache, revocations, bootstrap, 900);
        return new Fixture(service, repository, passwords, jwt, tokens, mail, cache, revocations);
    }

    private Document admin(String role, String plainPassword) {
        return new Document("_id", new ObjectId()).append("name", "Admin")
                .append("email", "admin@example.com").append("role", role)
                .append("password", new BCryptPasswordEncoder(4).encode(plainPassword))
                .append("rights", new Document()).append("status", true)
                .append("authTokenVersion", 0);
    }

    private record Fixture(AdminAuthService service, AuthRepository repository,
                           PasswordEncoder passwords, JwtService jwt, AuthTokenFactory tokens,
                           PasswordResetMailProvider mail, AuthDocumentCache cache,
                           TokenRevocationService revocations) { }
}
