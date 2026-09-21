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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class ConsultantExploreControllerTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "consultant", "phone", "9000000010", "phonePrefix", "91"));
    private final Document actor = new Document("_id", new ObjectId());
    private ConsultantExploreService service;
    private AuthUserService users;
    private ConsultantExploreController controller;

    @BeforeEach
    void setUp() {
        service = mock(ConsultantExploreService.class);
        users = mock(AuthUserService.class);
        when(users.load(principal)).thenReturn(actor);
        controller = new ConsultantExploreController(service, users,
                mock(MediaUploadService.class), new ObjectMapper());
    }

    @Test
    void listAlwaysUsesAuthenticatedConsultant() {
        Map<String, Object> listed = Map.of("count", 1, "list", List.of());
        when(service.list(actor, 2, 20, true)).thenReturn(listed);

        ApiResponse<?> response = controller.list(principal, 2, 20, true, false);

        assertSame(listed, response.getResult());
        verify(service).list(actor, 2, 20, true);
    }

    @Test
    void statusMutationIsActorScopedEvenIfBodyContainsConsultantId() {
        Map<String, Object> input = Map.of("postId", "P1", "status", false,
                "consultantId", "someone-else");
        Document updated = new Document("status", false);
        when(service.setStatus(actor, "P1", false)).thenReturn(updated);

        ApiResponse<?> response = controller.status(principal, input);

        assertSame(updated, response.getResult());
        verify(service).setStatus(actor, "P1", false);
    }

    @Test
    void deleteMutationIsActorScoped() {
        Document deleted = new Document("isDeleted", true);
        when(service.delete(actor, "P1")).thenReturn(deleted);

        ApiResponse<?> response = controller.delete(principal, "P1");

        assertSame(deleted, response.getResult());
        verify(service).delete(actor, "P1");
    }

    @Test
    void invalidTokenFailsClosed() {
        when(users.load(principal)).thenReturn(null);

        ApiResponse<?> response = controller.list(principal, 1, 10, false, false);

        assertFalse(response.isSuccess());
        assertEquals(500, response.getCode());
        assertEquals("Invalid token", response.getMessage());
        verifyNoInteractions(service);
    }
}
