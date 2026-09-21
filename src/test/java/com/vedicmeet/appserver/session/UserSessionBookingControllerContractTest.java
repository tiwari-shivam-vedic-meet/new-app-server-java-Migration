package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserSessionBookingControllerContractTest {

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(Map.of(
            "role", "user", "phone", "9000000001", "phonePrefix", "91"));

    @Test
    void disabledGateKeepsLegacyHttp200FailureEnvelope() {
        Fixture f = fixture(false, true, true, true, true, true);

        ResponseEntity<Map<String, Object>> response = f.controller.book(PRINCIPAL, request("chat"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("Java session booking is disabled", response.getBody().get("message"));
        verifyNoInteractions(f.users, f.bookings);
    }

    @Test
    void missingDurableIntegrationDependencyFailsClosed() {
        Fixture f = fixture(true, true, true, true, false, true);

        ResponseEntity<Map<String, Object>> response = f.controller.book(PRINCIPAL, request("chat"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Java booking integrations are not ready", response.getBody().get("message"));
        verify(f.bookings, never()).book(any(), any());
    }

    @Test
    void instantBookingRequiresCallSocketAndTimerGates() {
        Fixture f = fixture(true, false, true, true, true, true);

        ResponseEntity<Map<String, Object>> response = f.controller.book(PRINCIPAL, request("audio"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Java call execution is disabled", response.getBody().get("message"));
        verify(f.bookings, never()).book(any(), any());
    }

    @Test
    void scheduledBookingDoesNotRequireImmediateCallRuntime() {
        Fixture f = fixture(true, false, false, false, true, true);
        Document data = new Document("waitlistId", new ObjectId().toHexString());
        when(f.bookings.book(any(), any())).thenReturn(new SessionBookingService.BookingResult(data, false));

        ResponseEntity<Map<String, Object>> response = f.controller.book(PRINCIPAL, request("session-book"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertSame(data, response.getBody().get("data"));
    }

    @Test
    void successfulInstantBookingKeepsLegacySuccessEnvelope() {
        Fixture f = fixture(true, true, true, true, true, true);
        Document data = new Document("status", "waiting");
        when(f.bookings.book(any(), any())).thenReturn(new SessionBookingService.BookingResult(data, true));

        ResponseEntity<Map<String, Object>> response = f.controller.book(PRINCIPAL, request("chat"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertSame(data, response.getBody().get("data"));
    }

    @Test
    void offlineConsultantPreservesNodeHttp400AndNullData() {
        Fixture f = fixture(true, true, true, true, true, true);
        when(f.bookings.book(any(), any())).thenThrow(
                new SessionBookingService.ConsultantUnavailableException("Consultant is unavailable"));

        ResponseEntity<Map<String, Object>> response = f.controller.book(PRINCIPAL, request("chat"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("Consultant is unavailable", response.getBody().get("message"));
        assertTrue(response.getBody().containsKey("data"));
        assertNull(response.getBody().get("data"));
    }

    private static Fixture fixture(boolean booking, boolean call, boolean socket, boolean timer,
                                   boolean outbox, boolean chatReady) {
        UserSessionReadService reads = mock(UserSessionReadService.class);
        AuthUserService users = mock(AuthUserService.class);
        SessionBookingService bookings = mock(SessionBookingService.class);
        when(bookings.chatServerReady()).thenReturn(chatReady);
        when(users.load(PRINCIPAL)).thenReturn(new Document("_id", new ObjectId()));
        return new Fixture(new UserSessionController(reads, users, bookings,
                mock(UserSessionCouponService.class), mock(UserFixedSessionBookingService.class),
                mock(UserSessionCommandService.class), booking, false, false, call, socket,
                timer, outbox), users, bookings);
    }

    private static Map<String, Object> request(String mode) {
        return Map.of("consultantId", new ObjectId().toHexString(),
                "sessionMeta", Map.of("mode", mode));
    }

    private record Fixture(UserSessionController controller, AuthUserService users,
                           SessionBookingService bookings) {}
}
