package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.dto.LegacyAuthResponse;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.service.OtpAuthService;
import com.vedicmeet.appserver.auth.service.UserAuthService;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.JwtAuthFilter;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobileAuthControllerContractTest {

    @Test
    void consultationStatusIsPublicReadOnlyAndPreservesNodeEnvelope() {
        UserAuthService users = mock(UserAuthService.class);
        AuthRequests.DeviceRegistrationRequest request = new AuthRequests.DeviceRegistrationRequest();
        request.deviceUUID = "test-device";
        when(users.completedConsultationsForDevice("test-device")).thenReturn(3L);

        ResponseEntity<LegacyAuthResponse> response = controller(users).consultationStatus(request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isSuccess());
        assertNull(response.getBody().getCode());
        assertEquals("Consultation status checked successfully", response.getBody().getMessage());
        assertEquals(3L, response.getBody().getData());
    }

    @Test
    void userOtpFailureRemainsHttp200WithoutBodyCode() {
        UserAuthService users = mock(UserAuthService.class);
        MobileAuthController controller = controller(users);
        AuthRequests.VerifyOtpRequest request = new AuthRequests.VerifyOtpRequest();
        when(users.verifyUserOtp(request)).thenThrow(new AuthException("Invalid OTP"));

        ResponseEntity<LegacyAuthResponse> response = controller.verifyUserOtp(request);

        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertNull(response.getBody().getCode());
        assertEquals("Invalid OTP", response.getBody().getMessage());
    }

    @Test
    void consultantInvalidPhoneOrOtpRemainsHttp400Code400() {
        UserAuthService users = mock(UserAuthService.class);
        MobileAuthController controller = controller(users);
        AuthRequests.VerifyOtpRequest request = new AuthRequests.VerifyOtpRequest();
        when(users.verifyConsultantOtp(request)).thenThrow(AuthException.badRequest("Invalid OTP"));

        ResponseEntity<LegacyAuthResponse> response = controller.verifyConsultantOtp(request);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, response.getBody().getCode());
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void generalLoginFailureRemainsHttp500Code500() {
        UserAuthService users = mock(UserAuthService.class);
        MobileAuthController controller = controller(users);
        AuthRequests.LoginRequest request = new AuthRequests.LoginRequest();
        when(users.login(request)).thenThrow(new AuthException("INVALID_OTP"));

        ResponseEntity<LegacyAuthResponse> response = controller.login(request);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, response.getBody().getCode());
        assertEquals("INVALID_OTP", response.getBody().getMessage());
    }

    @Test
    void missingVerifyTokenReturnsHttp200WithAppDetails() {
        UserAuthService users = mock(UserAuthService.class);
        Document appDetails = new Document("forceUpdateType", "none");
        when(users.authAppDetails("android", "user")).thenReturn(appDetails);
        MobileAuthController controller = controller(users);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthFilter.AUTH_ERROR_ATTR, "missing");

        ResponseEntity<LegacyAuthResponse> response = controller.verify(
                null, request, "android", "user");

        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertEquals(appDetails, ((Map<?, ?>) response.getBody().getData()).get("appDetails"));
    }

    private MobileAuthController controller(UserAuthService users) {
        return new MobileAuthController(mock(OtpAuthService.class), users, mock(AuthUserService.class));
    }
}
