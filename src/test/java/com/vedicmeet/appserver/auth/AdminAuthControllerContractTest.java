package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.dto.AdminAuthRequests;
import com.vedicmeet.appserver.auth.dto.LegacyAuthResponse;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.service.AdminAuthService;
import com.vedicmeet.appserver.security.AuthUserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuthControllerContractTest {

    @Test
    void unauthorizedPublicAdminSignupUsesHttp401SecurityFix() {
        AdminAuthService service = mock(AdminAuthService.class);
        AdminAuthController controller = new AdminAuthController(service, mock(AuthUserService.class));
        AdminAuthRequests.SignupRequest request = new AdminAuthRequests.SignupRequest();
        when(service.signUp(request, null)).thenThrow(AuthException.unauthorized("Invalid token"));

        ResponseEntity<LegacyAuthResponse> response = controller.signUp(request, null);

        assertEquals(401, response.getStatusCode().value());
        assertEquals(401, response.getBody().getCode());
        assertFalse(response.getBody().isSuccess());
    }
}
