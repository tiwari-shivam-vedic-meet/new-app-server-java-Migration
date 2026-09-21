package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
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

class ConsultantOperationsControllerTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "consultant", "phone", "9000000010", "phonePrefix", "91"));
    private final Document actor = new Document("_id", new ObjectId());
    private AuthUserService users;
    private ConsultantPreferenceService preferences;
    private ConsultantFeedbackService feedback;
    private ConsultantSupportService support;
    private ConsultantAnalyticsService analytics;
    private ConsultantOperationsController controller;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserService.class);
        preferences = mock(ConsultantPreferenceService.class);
        feedback = mock(ConsultantFeedbackService.class);
        support = mock(ConsultantSupportService.class);
        analytics = mock(ConsultantAnalyticsService.class);
        when(users.load(principal)).thenReturn(actor);
        controller = new ConsultantOperationsController(users, preferences, feedback, support, analytics);
    }

    @Test
    void deviceUpdateUsesAuthenticatedConsultantRatherThanClientId() {
        Document updated = new Document("accountName", "A");
        when(preferences.updateDevice(actor, Map.of("token", "T"))).thenReturn(updated);

        Map<String, Object> response = controller.device(principal, Map.of("token", "T"));

        assertEquals(true, response.get("success"));
        assertSame(updated, response.get("data"));
        verify(preferences).updateDevice(actor, Map.of("token", "T"));
    }

    @Test
    void seedMissingDocumentKeepsSuccessfulNullDataContract() {
        when(preferences.seed("menu")).thenReturn(new ConsultantPreferenceService.SeedResult(false, null));

        Map<String, Object> response = controller.seed("menu");

        assertEquals(true, response.get("success"));
        assertEquals("No seed document for this type", response.get("message"));
    }

    @Test
    void feedbackFailureRemainsHttpBodyFailureInsteadOfThrowing() {
        when(feedback.submitFlag(eq(actor), eq("bad"), anyString()))
                .thenThrow(new IllegalArgumentException("Feedback not found"));

        Map<String, Object> response = controller.submitFlag(principal,
                Map.of("feedbackId", "bad", "reason", "spam"));

        assertFalse((Boolean) response.get("success"));
        assertEquals("Feedback not found", response.get("message"));
    }

    @Test
    void quickNoteListPreservesDataAndMessage() {
        List<Document> notes = List.of(new Document("note", "hello"));
        when(support.quickNotes(actor)).thenReturn(notes);

        Map<String, Object> response = controller.quickNotes(principal);

        assertEquals(notes, response.get("data"));
        assertEquals("Quick notes retrieved successfully", response.get("message"));
    }

    @Test
    void couponAnalyticsKeepsLegacyCodeField() {
        when(analytics.couponOffer(actor, null, null)).thenReturn(Map.of("overall", Map.of()));

        Map<String, Object> response = controller.couponOffer(principal, null, null);

        assertEquals(200, response.get("code"));
        assertEquals(true, response.get("success"));
    }

    @Test
    void missingAuthDocumentFailsClosed() {
        when(users.load(principal)).thenReturn(null);

        Map<String, Object> response = controller.performance(principal);

        assertEquals(false, response.get("success"));
        assertEquals("Invalid token", response.get("message"));
        verifyNoInteractions(analytics);
    }
}
