package com.vedicmeet.appserver.consultant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.media.MediaUploadService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class ConsultantSelfServiceControllerTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "consultant", "phone", "9000000010", "phonePrefix", "91"));
    private final Document actor = new Document("_id", new ObjectId());
    private ConsultantSelfService service;
    private AuthUserService users;
    private ConsultantSelfServiceController controller;

    @BeforeEach
    void setUp() {
        service = mock(ConsultantSelfService.class);
        users = mock(AuthUserService.class);
        when(users.load(principal)).thenReturn(actor);
        controller = new ConsultantSelfServiceController(service, users,
                mock(MediaUploadService.class), new ObjectMapper());
    }

    @Test
    void profileUpdateAlwaysUsesAuthenticatedConsultant() {
        Map<String, Object> input = Map.of("name", "Updated Name");
        Document updated = new Document("name", "Updated Name");
        when(service.updateProfile(actor, input, null)).thenReturn(updated);

        ApiResponse<?> response = controller.updateJson(principal, input);

        assertEquals(200, response.getCode());
        assertSame(updated, response.getResult());
        verify(service).updateProfile(actor, input, null);
    }

    @Test
    void warningDetailsAreActorScoped() {
        Document warning = new Document("message", "Please review");
        when(service.warningDetails(actor, "W1")).thenReturn(warning);

        ApiResponse<?> response = controller.warningDetails(principal, "W1", Map.of());

        assertSame(warning, response.getResult());
        verify(service).warningDetails(actor, "W1");
    }

    @Test
    void requestCannotSupplyAnotherConsultantIdentity() {
        Map<String, Object> input = Map.of("type", "PHONE", "consultantId", "someone-else");

        ApiResponse<?> response = controller.requestJson(principal, input);

        assertEquals(200, response.getCode());
        verify(service).addRequest(actor, input, null, null);
    }

    @Test
    void invalidTokenFailsClosedWithoutCallingService() {
        when(users.load(principal)).thenReturn(null);

        ApiResponse<?> response = controller.eventList(principal, 1, 10, null);

        assertFalse(response.isSuccess());
        assertEquals(500, response.getCode());
        assertEquals("Invalid token", response.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void logicalFailureKeepsLegacyBodyErrorContract() {
        when(service.acknowledgeWarning(actor, "bad"))
                .thenThrow(new IllegalArgumentException("WARNING_NOT_FOUND"));

        ApiResponse<?> response = controller.acknowledgeWarning(principal, Map.of("warningId", "bad"));

        assertFalse(response.isSuccess());
        assertEquals(500, response.getCode());
        assertEquals("WARNING_NOT_FOUND", response.getMessage());
    }
}
