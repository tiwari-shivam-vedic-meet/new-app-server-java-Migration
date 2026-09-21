package com.vedicmeet.appserver.security;

import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleInterceptorTest {

    @Test
    void validTokenAlsoRequiresCurrentMongoAccountAndCachesIt() throws Exception {
        AuthUserService users = mock(AuthUserService.class);
        Document account = new Document("_id", "u1");
        when(users.loadForHttp(any())).thenReturn(account);
        RoleInterceptor interceptor = new RoleInterceptor(users);
        MockHttpServletRequest request = requestWith(new AuthPrincipal(Map.of(
                "phone", "9000000001", "phonePrefix", "+91", "role", Role.USER)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("userOnly")));
        assertSame(account, request.getAttribute(AuthUserService.AUTH_DOCUMENT_ATTR));
    }

    @Test
    void validJwtForMissingUserIsRejectedWithNodeStyleHttp200() throws Exception {
        AuthUserService users = mock(AuthUserService.class);
        when(users.loadForHttp(any())).thenReturn(null);
        RoleInterceptor interceptor = new RoleInterceptor(users);
        MockHttpServletRequest request = requestWith(new AuthPrincipal(Map.of(
                "phone", "9000000001", "phonePrefix", "+91", "role", Role.USER)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, handler("userOnly")));
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("User not found"));
    }

    @Test
    void missingTokenPreservesExistingMobileContract() throws Exception {
        RoleInterceptor interceptor = new RoleInterceptor(mock(AuthUserService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthFilter.AUTH_ERROR_ATTR, "missing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, handler("userOnly")));
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("Authorization token required"));
    }

    private MockHttpServletRequest requestWith(AuthPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthFilter.PRINCIPAL_ATTR, principal);
        return request;
    }

    private HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Probe(), Probe.class.getMethod(name, HttpServletRequest.class));
    }

    static class Probe {
        @GetMapping
        @RequireRole(Role.USER)
        public void userOnly(HttpServletRequest request) { }
    }
}
