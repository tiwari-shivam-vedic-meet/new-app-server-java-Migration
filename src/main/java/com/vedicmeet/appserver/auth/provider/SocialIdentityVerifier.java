package com.vedicmeet.appserver.auth.provider;

import com.vedicmeet.appserver.auth.dto.AuthRequests;

/** Security boundary for social login. */
public interface SocialIdentityVerifier {
    SocialIdentity verify(AuthRequests.SocialLoginRequest request);

    record SocialIdentity(String provider, String subject, String email, boolean providerVerified) { }
}
