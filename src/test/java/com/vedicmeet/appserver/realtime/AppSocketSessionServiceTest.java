package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppSocketSessionServiceTest {

    private static final String USER = "507f1f77bcf86cd799439011";
    private static final String CONS = "507f1f77bcf86cd799439012";
    private static final String WAIT = "507f1f77bcf86cd799439013";

    @Test
    void switchRequestTargetsOnlyTheCounterparty() {
        Fixture f = fixture();
        AppSocketSessionService.Dispatch result = f.service.sessionSwitchPermission(
                Role.USER, USER, WAIT, "audio", "request");
        assertEquals(List.of(CONS), result.targets());
        assertEquals("request", result.payload().get("type"));
        assertEquals("audio", result.payload().get("channel"));
    }

    @Test
    void acceptedSwitchIsBroadcastToBothParticipants() {
        Fixture f = fixture();
        AppSocketSessionService.Dispatch result = f.service.sessionSwitchPermission(
                Role.CONSULTANT, CONS, WAIT, "chat", "accepted");
        assertEquals(List.of(CONS, USER), result.targets());
    }

    @Test
    void arbitraryOngoingActivityTargetIsRejected() {
        Fixture f = fixture();
        when(f.store.activeParticipants(Role.USER, USER, "attacker")).thenReturn(null);
        assertThrows(SecurityException.class, () -> f.service.ongoingActivity(
                Role.USER, USER, null, "attacker", "typing", "x"));
    }

    @Test
    void consultantDetailsRequireTheCallerToBelongToTheWaitlist() {
        AppSocketSessionStore store = mock(AppSocketSessionStore.class);
        AppSocketSessionService service = new AppSocketSessionService(store);
        assertThrows(SecurityException.class,
                () -> service.consultantDetails(Role.USER, USER, WAIT));
        verify(store, never()).consultantDetails(anyString());
    }

    @Test
    void userStatusKeepsTheExactNodeCallbackKeys() {
        Fixture f = fixture();
        when(f.store.userWaitlists(USER)).thenReturn(List.of(new Document("status", "waiting")));
        when(f.store.fixedSessionWaitlists(USER)).thenReturn(List.of());
        var result = f.service.waitlistStatus(Role.USER, USER);
        assertTrue(result.containsKey("entry"));
        assertTrue(result.containsKey("progressingEntry"));
        assertTrue(result.containsKey("fixedSessionEntry"));
    }

    @Test
    void onlyUserCanCancelAndFixedCancellationReturnsEmptyArray() {
        Fixture f = fixture();
        when(f.store.cancelFixedSession(WAIT, USER, "changed mind"))
                .thenReturn(new Document("status", "canceled"));
        assertEquals(List.of(), f.service.cancelSession(Role.USER, USER, WAIT,
                "changed mind", "fixed_session"));
        assertThrows(SecurityException.class, () -> f.service.cancelSession(
                Role.CONSULTANT, CONS, WAIT, null, "private_call"));
    }

    @Test
    void rejoinCannotReviveAnotherUsersWaitlist() {
        Fixture f = fixture();
        when(f.store.rejoinWaitlist(WAIT, CONS, USER)).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> f.service.rejoinWaitlist(Role.USER, USER, CONS, WAIT));
    }

    @Test
    void messageLimitClaimMapsToLegacyCallback() {
        Fixture f = fixture();
        when(f.store.claimWaitlistMessage(WAIT, Role.CONSULTANT, CONS, "hello"))
                .thenReturn(new AppSocketSessionStore.MessageClaim(new Document(), 2));
        assertEquals(Boolean.TRUE, f.service.canSendMessage(
                Role.CONSULTANT, CONS, WAIT, "hello").get("canSend"));
        when(f.store.claimWaitlistMessage(WAIT, Role.CONSULTANT, CONS, "hello"))
                .thenReturn(null);
        assertEquals("Message limit reached", f.service.canSendMessage(
                Role.CONSULTANT, CONS, WAIT, "hello").get("message"));
    }

    @Test
    void presenceFailureIsNonFatal() {
        Fixture f = fixture();
        doThrow(new RuntimeException("Mongo unavailable")).when(f.store)
                .updatePresence(Role.USER, USER, "online", 0);
        assertDoesNotThrow(() -> f.service.presence(Role.USER, USER, "online", 0));
    }

    private Fixture fixture() {
        AppSocketSessionStore store = mock(AppSocketSessionStore.class);
        Document waitlist = new Document("_id", WAIT).append("user_id", USER)
                .append("consultant_id", CONS).append("status", "progress");
        var participants = new AppSocketSessionStore.Participants(waitlist, USER, CONS);
        when(store.participants(WAIT, Role.USER, USER)).thenReturn(participants);
        when(store.participants(WAIT, Role.CONSULTANT, CONS)).thenReturn(participants);
        when(store.consultantDetails(CONS)).thenReturn(new Document("accountName", "Guru"));
        return new Fixture(new AppSocketSessionService(store), store);
    }

    private record Fixture(AppSocketSessionService service, AppSocketSessionStore store) {}
}
