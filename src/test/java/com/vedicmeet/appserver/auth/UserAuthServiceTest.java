package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.OtpProvider;
import com.vedicmeet.appserver.auth.provider.SocialIdentityVerifier;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.service.AuthAppVersionService;
import com.vedicmeet.appserver.auth.service.AuthTokenFactory;
import com.vedicmeet.appserver.auth.service.DeviceService;
import com.vedicmeet.appserver.auth.service.OtpAuthService;
import com.vedicmeet.appserver.auth.service.RegistrationIntegrationService;
import com.vedicmeet.appserver.auth.service.UserAuthService;
import com.vedicmeet.appserver.auth.service.ZodiacCalculator;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.security.TokenRevocationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAuthServiceTest {

    @Test
    void verifyExistingUserReturnsLegacyShapeAndRepairsCommunityMapping() {
        Fixture f = fixture();
        AuthRequests.VerifyOtpRequest request = otpRequest();
        Document user = activeUser();
        when(f.otp.verify(request, "user")).thenReturn(okOtp());
        when(f.repository.findUserByPhone("9000000001", "91", false)).thenReturn(user);
        when(f.tokens.accountToken(Role.USER, user)).thenReturn("jwt-user");

        Map<String, Object> result = f.service.verifyUserOtp(request);

        assertEquals(false, result.get("isNewUser"));
        assertEquals("jwt-user", result.get("token"));
        assertEquals(user, result.get("user"));
        verify(f.repository).ensureFamilyCommunityMapping(user.getObjectId("_id"));
    }

    @Test
    void verifyUnknownUserReturnsRegistrationDecisionWithoutCreatingData() {
        Fixture f = fixture();
        AuthRequests.VerifyOtpRequest request = otpRequest();
        when(f.otp.verify(request, "user")).thenReturn(okOtp());

        Map<String, Object> result = f.service.verifyUserOtp(request);

        assertEquals(true, result.get("isNewUser"));
        verify(f.repository, never()).insertUser(any());
        verify(f.tokens, never()).accountToken(anyString(), any());
    }

    @Test
    void registerCreatesAccountWalletCommunityAndBestEffortIntegrations() {
        Fixture f = fixture();
        AuthRequests.UserRegistrationRequest request = registrationRequest();
        ObjectId id = new ObjectId();
        when(f.repository.generateReferCode(4)).thenReturn("VEDI12");
        when(f.repository.nextUserId()).thenReturn("VM2691");
        when(f.repository.insertUser(any())).thenAnswer(invocation -> {
            Document user = invocation.getArgument(0);
            user.put("_id", id);
            return user;
        });
        when(f.tokens.accountToken(eq(Role.USER), any())).thenReturn("jwt-created");

        UserAuthService.RegistrationResult result = f.service.registerUser(request, "android");

        assertFalse(result.existing());
        assertEquals("jwt-created", result.token());
        assertEquals("9000000001", result.user().get("details", Document.class).getString("phone"));
        assertEquals("aries", result.user().getString("zodiac"));
        verify(f.otp).requireRegistrationProof("91", "9000000001");
        verify(f.repository).createWalletForUser(id);
        verify(f.repository).ensureFamilyCommunityMapping(id);
        verify(f.integrations).userRegistered(eq(result.user()), eq("fcm-1"), any());
    }

    @Test
    void phoneLoginAutoCreatesLegacyUserThenRegistersDevice() {
        Fixture f = fixture();
        AuthRequests.LoginRequest request = phoneLogin("user");
        ObjectId id = new ObjectId();
        when(f.otp.verify(any(AuthRequests.VerifyOtpRequest.class), eq("user"))).thenReturn(okOtp());
        when(f.repository.generateReferCode(4)).thenReturn("VEDI21");
        when(f.repository.nextUserId()).thenReturn("VM2692");
        when(f.repository.insertUser(any())).thenAnswer(invocation -> {
            Document user = invocation.getArgument(0);
            user.put("_id", id);
            return user;
        });
        when(f.devices.register(eq(Role.USER), any(), anyString(), anyString(), anyString(),
                anyString(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(f.tokens.accountToken(eq(Role.USER), any())).thenReturn("jwt-login");

        Map<String, Object> result = f.service.login(request);

        assertEquals(true, result.get("isUserNew"));
        assertEquals("jwt-login", result.get("accessToken"));
        verify(f.repository).createWalletForUser(id);
        verify(f.repository).ensureFamilyCommunityMapping(id);
    }

    @Test
    void consultantCannotLoginUntilAdminApproval() {
        Fixture f = fixture();
        AuthRequests.LoginRequest request = emailLogin("cons");
        Document consultant = activeConsultant().append("isAdminVerify", false);
        when(f.repository.findConsultantByEmail("consultant@example.com", false)).thenReturn(consultant);

        AuthException error = assertThrows(AuthException.class, () -> f.service.login(request));

        assertEquals("CONSULTANT_NOT_VERIFY_BY_ADMIN", error.getMessage());
        verify(f.devices, never()).register(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void socialLoginPreservesNodeNestedLegacyResponse() {
        Fixture f = fixture();
        AuthRequests.SocialLoginRequest request = new AuthRequests.SocialLoginRequest();
        request.userType = "user";
        request.email = "USER@example.com";
        when(f.social.verify(request)).thenReturn(
                new SocialIdentityVerifier.SocialIdentity("legacy-email", null, "user@example.com", false));

        Map<String, Object> result = f.service.socialLogin(request);

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("isUserNew"));
        assertTrue(result.get("data") instanceof Map);
        assertEquals("", ((Map<?, ?>) result.get("data")).get("token"));
    }

    @Test
    void logoutAllInvalidatesCacheAndRevokesCallingToken() {
        Fixture f = fixture();
        AuthRequests.LogoutRequest request = new AuthRequests.LogoutRequest();
        request.userType = "user";
        request.logOutFrom = "all";
        Document user = activeUser();
        Document updated = new Document(user).append("authTokenVersion", 1);
        AuthPrincipal principal = new AuthPrincipal(Map.of(
                "role", Role.USER, "_id", user.getObjectId("_id").toHexString(), "jti", "jti-1"));
        when(f.repository.logout(AppConstants.Collections.USERS, user.getObjectId("_id"),
                "all", null, null)).thenReturn(updated);

        f.service.logout(request, principal, user);

        verify(f.authCache).invalidate(Role.USER, user);
        verify(f.authCache).invalidate(Role.USER, updated);
        verify(f.revocations).revoke(principal);
    }

    @Test
    void accountDeletionUsesSoftDeleteAndCancelsActiveUserWaitlists() {
        Fixture f = fixture();
        Document user = activeUser();
        AuthPrincipal principal = new AuthPrincipal(Map.of(
                "role", Role.USER, "_id", user.getObjectId("_id").toHexString(), "jti", "jti-2"));

        Map<String, Object> result = f.service.deleteAccount("user", principal, user);

        assertEquals(true, result.get("success"));
        verify(f.repository).softDeleteUser(user.getObjectId("_id"));
        verify(f.repository).cancelUserWaitlists(user.getObjectId("_id"));
        verify(f.revocations).revoke(principal);
    }

    private Fixture fixture() {
        AuthRepository repository = mock(AuthRepository.class);
        OtpAuthService otp = mock(OtpAuthService.class);
        DeviceService devices = mock(DeviceService.class);
        AuthTokenFactory tokens = mock(AuthTokenFactory.class);
        SocialIdentityVerifier social = mock(SocialIdentityVerifier.class);
        RegistrationIntegrationService integrations = mock(RegistrationIntegrationService.class);
        AuthDocumentCache authCache = mock(AuthDocumentCache.class);
        TokenRevocationService revocations = mock(TokenRevocationService.class);
        AuthAppVersionService versions = mock(AuthAppVersionService.class);
        UserAuthService service = new UserAuthService(repository, new AuthRequestValidator(), otp,
                devices, tokens, social, integrations, authCache, revocations,
                new ZodiacCalculator(), new AppConstants("test-bucket", "ap-south-1"), versions, true);
        return new Fixture(service, repository, otp, devices, tokens, social,
                integrations, authCache, revocations);
    }

    private AuthRequests.VerifyOtpRequest otpRequest() {
        AuthRequests.VerifyOtpRequest request = new AuthRequests.VerifyOtpRequest();
        request.phone = "9000000001";
        request.phonePrefix = "91";
        request.otp = "1234";
        return request;
    }

    private AuthRequests.UserRegistrationRequest registrationRequest() {
        AuthRequests.UserRegistrationRequest request = new AuthRequests.UserRegistrationRequest();
        request.phone = "9000000001";
        request.phonePrefix = "91";
        request.email = "user@example.com";
        request.name = "User";
        request.dob = "2000-04-01";
        request.fcmToken = "fcm-1";
        request.deviceUUID = "uuid-1";
        return request;
    }

    private AuthRequests.LoginRequest phoneLogin(String type) {
        AuthRequests.LoginRequest request = new AuthRequests.LoginRequest();
        request.userType = type;
        request.phone = "9000000001";
        request.phonePrefix = "91";
        request.otp = "1234";
        request.deviceToken = "device-1";
        request.fcmToken = "fcm-1";
        request.deviceUUID = "uuid-1";
        request.deviceType = "android";
        return request;
    }

    private AuthRequests.LoginRequest emailLogin(String type) {
        AuthRequests.LoginRequest request = phoneLogin(type);
        request.phone = null;
        request.phonePrefix = null;
        request.otp = null;
        request.email = "consultant@example.com";
        return request;
    }

    private Document activeUser() {
        return new Document("_id", new ObjectId())
                .append("details", new Document("phone", "9000000001").append("phonePrefix", "91"))
                .append("status", true).append("isDeleted", false).append("authTokenVersion", 0);
    }

    private Document activeConsultant() {
        return new Document("_id", new ObjectId())
                .append("details", new Document("email", "consultant@example.com"))
                .append("status", true).append("isDeleted", false).append("isAdminVerify", true);
    }

    private OtpProvider.OtpResult okOtp() {
        return new OtpProvider.OtpResult(true, "verified", Map.of());
    }

    private record Fixture(UserAuthService service, AuthRepository repository, OtpAuthService otp,
                           DeviceService devices, AuthTokenFactory tokens,
                           SocialIdentityVerifier social, RegistrationIntegrationService integrations,
                           AuthDocumentCache authCache, TokenRevocationService revocations) { }
}
