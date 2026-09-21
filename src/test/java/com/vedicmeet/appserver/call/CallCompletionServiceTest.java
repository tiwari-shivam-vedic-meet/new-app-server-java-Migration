package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallCompletionServiceTest {

    @Test
    void redisCallMustBeAccepted_beforeAnyMongoSettlementRead() {
        CallCompletionStore store = mock(CallCompletionStore.class);
        when(store.getCallHash("R")).thenReturn(Map.of("status", "initiated"));

        CallCompletionService.Result result = service(store).complete("R", "U", "C",
                "completed", "user");

        assertEquals(CallCompletionService.Outcome.REDIS_NOT_ACCEPTED, result.outcome());
        verify(store).getCallHash("R");
        verifyNoMoreInteractions(store);
    }

    @Test
    void firstPurchasePolicy_canBlockPayout_andPropagatesReplayAndActualDeduction() {
        CallCompletionStore store = readyStore();
        Document completed = waitlist();
        CallCompletionStore.CompletionPolicy policy =
                new CallCompletionStore.CompletionPolicy(true, true, "device_previous_taken");
        when(store.evaluateCompletionPolicy(eq("C"), eq("user"), any())).thenReturn(policy);
        when(store.claimAndApply(eq("R"), eq("U"), eq("C"), eq("completed"), eq("user"),
                any(), any(), any(), any(), eq(policy)))
                .thenReturn(new CallCompletionStore.ApplyResult(true, 9.5, completed));

        CallCompletionService.Result result = service(store).complete("R", null, null,
                "completed", "user");

        assertEquals(CallCompletionService.Outcome.COMPLETED, result.outcome());
        assertEquals(9.5, result.actualUserDeduction());
        ArgumentCaptor<CallCompletionStore.Amounts> amounts =
                ArgumentCaptor.forClass(CallCompletionStore.Amounts.class);
        verify(store).claimAndApply(eq("R"), eq("U"), eq("C"), eq("completed"), eq("user"),
                amounts.capture(), any(), any(), any(), eq(policy));
        assertEquals(0, amounts.getValue().consultantAmount(), 0.001,
                "conversion policy must suppress the consultant credit");
        verify(store).emitLeaveRooms("U", "C", completed, "user", true);
        verify(store).enqueuePostCallWork("U", "C", completed, amounts.getValue(), 9.5, "user");
        verify(store).clearRuntime("R", "C");
        verify(store).deleteCallInitiated("R", "U", "C");
        verify(store).callNextWaitingUser(any(), eq("R"), eq("user"));
    }

    @Test
    void failedAtomicClaim_isDuplicate_andRunsNoSideEffects() {
        CallCompletionStore store = readyStore();
        CallCompletionStore.CompletionPolicy policy =
                new CallCompletionStore.CompletionPolicy(false, false, null);
        when(store.evaluateCompletionPolicy(eq("C"), eq("user"), any())).thenReturn(policy);
        when(store.claimAndApply(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any()))
                .thenReturn(new CallCompletionStore.ApplyResult(false, 0, null));

        CallCompletionService.Result result = service(store).complete("R", null, null,
                "completed", "user");

        assertEquals(CallCompletionService.Outcome.DUPLICATE, result.outcome());
        verify(store, never()).clearRuntime(anyString(), anyString());
        verify(store, never()).enqueuePostCallWork(anyString(), anyString(), any(), any(), anyDouble(), any());
        verify(store, never()).callNextWaitingUser(any(), anyString(), any());
    }

    private CallCompletionService service(CallCompletionStore store) {
        return new CallCompletionService(store, new CallTimeoutSettlementService());
    }

    private CallCompletionStore readyStore() {
        CallCompletionStore store = mock(CallCompletionStore.class);
        when(store.getCallHash("R")).thenReturn(Map.of(
                "status", "accepted", "userId", "U", "consultantId", "C"));
        when(store.findWaitlist("R")).thenReturn(waitlist());
        when(store.findUser("U")).thenReturn(new Document("_id", "U").append("wallet", 100));
        when(store.findConsultant("C")).thenReturn(new Document("_id", "C")
                .append("price", new Document("platformShare", 60)));
        return store;
    }

    private Document waitlist() {
        return new Document("_id", "R").append("status", "progress")
                .append("user_id", "U").append("consultant_id", "C")
                .append("used_for", "private_call")
                .append("session_info", new Document("mode", "chat")
                        .append("price", 10).append("basePrice", 10))
                .append("coupon", new Document())
                .append("timeLap", new Document("startTime",
                        new java.util.Date(System.currentTimeMillis() - 60_000)));
    }
}
