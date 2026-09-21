package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.call.CallOpenStore;
import com.vedicmeet.appserver.call.JavaCallTimerService;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.session.SessionBookingStore;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveEventServiceTest {

    @Test
    void firstEligibleUserIsPersistedAndArmedAsProgress() {
        Fixture f = fixture();

        LiveEventService.JoinResult result = f.service.joinWaitlist(f.userId, Role.USER,
                f.eventId, Map.of("firstName", "Asha"));

        assertEquals("connect_now", result.action());
        assertEquals("progress", result.waitlist().getString("status"));
        assertEquals("live_event", result.waitlist().getString("used_for"));
        assertEquals("thread-1", result.roomId());
        assertEquals(600L, ((Number) result.waitlist().get("requested_time")).longValue());
        verify(f.store).insertWaitlist(result.waitlist());
        verify(f.runtime).claimConsultantCall(eq(f.consultantId), anyString(), eq(900L));
        verify(f.runtime).writeCallHash(anyString(), argThat(hash ->
                "accepted".equals(hash.get("status")) && "live_event".equals(hash.get("usedFor"))), eq(900L));
        verify(f.callTimers).scheduleTimeout(anyString(), eq(f.userId), eq(f.consultantId), eq(600L));
    }

    @Test
    void busyConsultantLeavesNewEntryWaitingWithoutArmingMoneyTimer() {
        Fixture f = fixture();
        when(f.store.consultantBusy(eq(f.consultantId), eq(f.eventId), anyString())).thenReturn(true);

        LiveEventService.JoinResult result = f.service.joinWaitlist(f.userId, Role.USER,
                f.eventId, Map.of());

        assertEquals("waitlist_created", result.action());
        assertEquals("waiting", result.waitlist().getString("status"));
        verify(f.runtime, never()).writeCallHash(anyString(), anyMap(), anyLong());
        verifyNoInteractions(f.callTimers);
    }

    @Test
    void minimumFiveMinuteBalanceIsCheckedBeforeCreatingChatThread() {
        Fixture f = fixture();
        f.user.put("wallet", 49.0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> f.service.joinWaitlist(f.userId, Role.USER, f.eventId, Map.of()));

        assertTrue(error.getMessage().contains("Minimum balance required is 50 coins"));
        verify(f.chatServer, never()).createThread(anyMap());
        verify(f.store, never()).insertWaitlist(any());
    }

    @Test
    void activeEntryPreventsDuplicateBeforeRemoteSideEffect() {
        Fixture f = fixture();
        when(f.store.activeUserWaitlist(f.userId, f.consultantId))
                .thenReturn(new Document("status", "waiting"));

        assertThrows(IllegalStateException.class,
                () -> f.service.joinWaitlist(f.userId, Role.USER, f.eventId, Map.of()));

        verify(f.chatServer, never()).createThread(anyMap());
    }

    @Test
    void runtimeArmFailureRevertsProgressAndReleasesConsultantOwner() {
        Fixture f = fixture();
        doThrow(new RuntimeException("redis down")).when(f.runtime)
                .writeCallHash(anyString(), anyMap(), anyLong());

        assertThrows(IllegalStateException.class,
                () -> f.service.joinWaitlist(f.userId, Role.USER, f.eventId, Map.of()));

        verify(f.store).revertProgress(anyString(), contains("runtime failed"));
        verify(f.runtime).releaseConsultantCall(eq(f.consultantId), anyString());
    }

    @Test
    void onlyOwningConsultantCanCallNextUser() {
        Fixture f = fixture();
        String another = new ObjectId().toHexString();

        assertThrows(IllegalStateException.class,
                () -> f.service.callNext(another, Role.CONSULTANT, f.eventId));
        assertThrows(IllegalStateException.class,
                () -> f.service.callNext(f.consultantId, Role.USER, f.eventId));
        verify(f.store, never()).nextWaiting(anyString(), anyString());
    }

    private static Fixture fixture() {
        LiveEventStore store = mock(LiveEventStore.class);
        LiveEventChatService chat = mock(LiveEventChatService.class);
        LiveEventReplayTimerService replay = mock(LiveEventReplayTimerService.class);
        SessionBookingStore leases = mock(SessionBookingStore.class);
        CallOpenStore runtime = mock(CallOpenStore.class);
        JavaCallTimerService timers = mock(JavaCallTimerService.class);
        ChatServerClient chatServer = mock(ChatServerClient.class);
        String eventId = new ObjectId().toHexString();
        String userId = new ObjectId().toHexString();
        String consultantId = new ObjectId().toHexString();
        Document event = new Document("_id", new ObjectId(eventId))
                .append("consultant_id", consultantId);
        Document user = new Document("_id", new ObjectId(userId)).append("name", "Asha")
                .append("wallet", 100.0).append("subscription", "none");
        Document consultant = new Document("_id", new ObjectId(consultantId))
                .append("accountName", "Guru").append("price", new Document("privateCallOnLive", 10.0)
                        .append("platformShare", 60.0));
        when(store.event(eventId)).thenReturn(event);
        when(store.user(userId)).thenReturn(user);
        when(store.consultant(consultantId)).thenReturn(consultant);
        when(store.activeUserWaitlist(userId, consultantId)).thenReturn(null);
        when(store.consultantBusy(eq(consultantId), eq(eventId), anyString())).thenReturn(false);
        when(chatServer.createThread(anyMap())).thenReturn(Map.of("success", true, "data", "thread-1"));
        when(leases.acquire(anyString(), any(Duration.class))).thenAnswer(invocation ->
                new SessionBookingStore.Lease(invocation.getArgument(0), "token"));
        when(runtime.claimConsultantCall(eq(consultantId), anyString(), anyLong())).thenReturn(true);
        when(store.claimProgress(anyString())).thenAnswer(invocation ->
                new Document("_id", new ObjectId((String) invocation.getArgument(0)))
                        .append("timeLap", new Document("startTime", new java.util.Date())));
        LiveEventService service = new LiveEventService(store, chat, replay, leases, runtime,
                timers, chatServer);
        return new Fixture(service, store, runtime, timers, chatServer, user, eventId, userId,
                consultantId);
    }

    private record Fixture(LiveEventService service, LiveEventStore store, CallOpenStore runtime,
                           JavaCallTimerService callTimers, ChatServerClient chatServer,
                           Document user, String eventId, String userId, String consultantId) {}
}
