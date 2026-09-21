package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.OtpProvider;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.service.DeviceService;
import com.vedicmeet.appserver.auth.service.OtpAuthService;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.cache.CacheService;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpAuthServiceTest {

    @Test
    void sendForNewUserDelegatesToProvider() {
        Fixture f = fixture(false);
        when(f.provider.send("91", "9000000001"))
                .thenReturn(new OtpProvider.OtpResult(true, "sent", Map.of()));

        assertEquals(true, f.service.send(send("user")).success());
        verify(f.provider).send("91", "9000000001");
    }

    @Test
    void consultantMustExistAndBeApprovedBeforeOtpIsSent() {
        Fixture f = fixture(false);
        when(f.repository.findConsultantByPhone("9000000001", "91", false))
                .thenReturn(new Document("status", true).append("isAdminVerify", false));

        AuthException error = assertThrows(AuthException.class, () -> f.service.send(send("cons")));
        assertEquals("CONSULTANT_NOT_VERIFY_BY_ADMIN", error.getMessage());
        verify(f.provider, never()).send(anyString(), anyString());
    }

    @Test
    void successfulVerificationCreatesShortLivedRegistrationProof() {
        Fixture f = fixture(false);
        AuthRequests.VerifyOtpRequest request = verifyRequest();
        when(f.provider.verify("91", "9000000001", "1234"))
                .thenReturn(new OtpProvider.OtpResult(true, "verified", Map.of()));

        f.service.verify(request, "user");

        verify(f.cache).set("auth:otp-verified:user:91:9000000001", true, 600);
    }

    @Test
    void requiredRegistrationProofIsConsumedOnce() {
        Fixture f = fixture(true);
        when(f.cache.get("auth:otp-verified:user:91:9000000001", Boolean.class)).thenReturn(true);

        f.service.requireRegistrationProof("91", "9000000001");

        verify(f.cache).invalidate("auth:otp-verified:user:91:9000000001");
    }

    private Fixture fixture(boolean proofRequired) {
        AuthRepository repository = mock(AuthRepository.class);
        DeviceService devices = mock(DeviceService.class);
        OtpProvider provider = mock(OtpProvider.class);
        CacheService cache = mock(CacheService.class);
        OtpAuthService service = new OtpAuthService(repository, new AuthRequestValidator(),
                devices, provider, cache, 600, proofRequired);
        return new Fixture(service, repository, provider, cache);
    }

    private AuthRequests.SendOtpRequest send(String type) {
        AuthRequests.SendOtpRequest request = new AuthRequests.SendOtpRequest();
        request.userType = type;
        request.phone = "9000000001";
        request.phonePrefix = "91";
        request.deviceToken = "device-1";
        return request;
    }

    private AuthRequests.VerifyOtpRequest verifyRequest() {
        AuthRequests.VerifyOtpRequest request = new AuthRequests.VerifyOtpRequest();
        request.phone = "9000000001";
        request.phonePrefix = "91";
        request.otp = "1234";
        return request;
    }

    private record Fixture(OtpAuthService service, AuthRepository repository,
                           OtpProvider provider, CacheService cache) { }
}
