package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsultantSessionControllerTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "consultant", "phone", "9000000010", "phonePrefix", "91"));
    private final Document actor = new Document("_id", new ObjectId());
    private AuthUserService users;
    private ConsultantSessionReadService reads;
    private ConsultantSessionCallService calls;
    private ConsultantSessionAssignmentService assignments;
    private ConsultantRecordingService recordings;

    @BeforeEach
    void setup() {
        users = mock(AuthUserService.class);
        reads = mock(ConsultantSessionReadService.class);
        calls = mock(ConsultantSessionCallService.class);
        assignments = mock(ConsultantSessionAssignmentService.class);
        recordings = mock(ConsultantRecordingService.class);
        when(users.load(principal)).thenReturn(actor);
    }

    @Test
    void sessionWritesFailClosedWhenIndependentGateIsOff() {
        ConsultantSessionController controller = controller(false, false, false);
        Map<String, Object> response = controller.createLiveEvent(principal, Map.of("event_name", "Test"));
        assertEquals(false, response.get("success"));
        assertEquals("Java consultant-session execution is disabled", response.get("message"));
        verifyNoInteractions(users, calls, assignments, recordings);
    }

    @Test
    void historyReadRemainsAvailableInShadowModeAndUsesAuthenticatedActor() {
        List<Document> rows = List.of(new Document("status", "completed"));
        when(reads.waitlistHistory(actor, "all", "private_call")).thenReturn(rows);
        ConsultantSessionController controller = controller(false, false, false);

        Map<String, Object> response = controller.history(principal, "all", "private_call");

        assertEquals(true, response.get("success"));
        assertSame(rows, response.get("data"));
        verify(reads).waitlistHistory(actor, "all", "private_call");
    }

    @Test
    void callActionsRequireTheCallExecutionGateEvenWhenSessionGateIsOn() {
        ConsultantSessionController controller = controller(true, false, false);
        Map<String, Object> response = controller.acceptIncoming(principal, Map.of("roomId", "R"));
        assertEquals(false, response.get("success"));
        assertEquals("Java call execution is disabled", response.get("message"));
        verifyNoInteractions(calls);
    }

    @Test
    void assignmentBusinessCodeIsPreservedForMobileClient() {
        when(assignments.acceptFixedSession(actor, "W")).thenReturn(
                new ConsultantSessionAssignmentService.AssignmentResult(false,
                        "SESSION_ALREADY_ACCEPTED_BY_ANOTHER_CONSULTANT",
                        "Session already accepted by another consultant", null));
        ConsultantSessionController controller = controller(true, true, false);

        Map<String, Object> response = controller.acceptFixed(principal, Map.of("waitlistId", "W"));

        assertEquals(false, response.get("success"));
        assertEquals("SESSION_ALREADY_ACCEPTED_BY_ANOTHER_CONSULTANT", response.get("code"));
    }

    @Test
    void immediateMoneyRefundHasAnAdditionalIndependentGate() {
        ConsultantSessionController controller = controller(true, true, false);
        Map<String, Object> response = controller.immediateRefund(principal, Map.of("waitlistId", "W"));
        assertEquals(false, response.get("success"));
        assertEquals("Java immediate refund execution is disabled", response.get("message"));
    }

    @Test
    void recordingStopPreservesIdempotentMessage() {
        when(recordings.stop(actor, Map.of("roomName", "room"))).thenReturn(
                new ConsultantRecordingService.StopResult("No active recording found to stop", null));
        ConsultantSessionController controller = controller(true, true, false);

        Map<String, Object> response = controller.stopRecording(principal, Map.of("roomName", "room"));

        assertEquals(true, response.get("success"));
        assertEquals("No active recording found to stop", response.get("message"));
    }

    private ConsultantSessionController controller(boolean session, boolean call, boolean refund) {
        return new ConsultantSessionController(users, reads, calls,
                mock(ConsultantLiveEventCommandService.class), assignments,
                mock(ConsultantRefundService.class), recordings, session, call, refund);
    }
}
