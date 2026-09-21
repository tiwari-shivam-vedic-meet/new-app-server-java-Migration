package com.vedicmeet.appserver.auth.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthProviderSafetyTest {

    @Test
    void disabledMsg91FailsClosedBeforeAnyNetworkRequest() {
        Msg91OtpProvider provider = new Msg91OtpProvider(
                false, "https://example.invalid", "", "", new ObjectMapper());

        AuthException error = assertThrows(AuthException.class,
                () -> provider.send("91", "9000000001"));

        assertEquals("OTP provider is not configured", error.getMessage());
    }

    @Test
    void legacySocialEmailIsExplicitAndNormalized() {
        GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(
                false, true, "", "https://example.invalid", new ObjectMapper());
        AuthRequests.SocialLoginRequest request = new AuthRequests.SocialLoginRequest();
        request.email = " User@Example.COM ";

        SocialIdentityVerifier.SocialIdentity identity = verifier.verify(request);

        assertEquals("user@example.com", identity.email());
        assertEquals("legacy-email", identity.provider());
        assertFalse(identity.providerVerified());
    }

    @Test
    void legacyEmailAndUnconfiguredProviderBothFailClosedWhenCompatibilityIsOff() {
        GoogleSocialIdentityVerifier verifier = new GoogleSocialIdentityVerifier(
                false, false, "", "https://example.invalid", new ObjectMapper());
        AuthRequests.SocialLoginRequest request = new AuthRequests.SocialLoginRequest();
        request.email = "user@example.com";
        assertThrows(AuthException.class, () -> verifier.verify(request));

        request.provider = "google";
        request.identityToken = "not-sent-anywhere";
        assertThrows(AuthException.class, () -> verifier.verify(request));
    }
}
