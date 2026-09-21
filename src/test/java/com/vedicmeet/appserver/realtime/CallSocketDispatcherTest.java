package com.vedicmeet.appserver.realtime;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful call-socket routing of {@link CallSocketDispatcher} (Node app.js L321-473). */
class CallSocketDispatcherTest {

    @Test
    void acceptCall_asUser_mapsUserIdOnly() {
        Fake b = new Fake();
        new CallSocketDispatcher(b).acceptCall(CallSocketDispatcher.USER, "U1", "R", "audio");
        assertEquals("R|U1|null|audio", b.accept);
    }

    @Test
    void acceptCall_asConsultant_mapsConsultantIdOnly() {
        Fake b = new Fake();
        new CallSocketDispatcher(b).acceptCall(CallSocketDispatcher.CONSULTANT, "C1", "R", null);
        assertEquals("R|null|C1|null", b.accept);
    }

    @Test
    void cancelCall_sessionByConsultant_forcesSettlement() {
        Fake b = new Fake();
        b.progressSession = new Document("_id", "R").append("user_id", "U1").append("consultant_id", "C1");
        var out = new CallSocketDispatcher(b).cancelCall(CallSocketDispatcher.CONSULTANT, "C1", "R", true);
        assertEquals(CallSocketDispatcher.CancelResult.SESSION_CONSULTANT_TIMEOUT, out);
        assertEquals("accepted", b.status);
        assertEquals("R|U1|C1|null|cons", b.timeout);
    }

    @Test
    void cancelCall_sessionByConsultant_noWaitlist_isNoOp() {
        Fake b = new Fake();
        b.progressSession = null;
        var out = new CallSocketDispatcher(b).cancelCall(CallSocketDispatcher.CONSULTANT, "C1", "R", true);
        assertEquals(CallSocketDispatcher.CancelResult.SESSION_NOT_FOUND_NOOP, out);
        assertNull(b.timeout);
    }

    @Test
    void cancelCall_plain_delegatesCancel() {
        Fake b = new Fake();
        var out = new CallSocketDispatcher(b).cancelCall(CallSocketDispatcher.USER, "U1", "R", false);
        assertEquals(CallSocketDispatcher.CancelResult.PLAIN_CANCEL, out);
        assertEquals("R|U1|null", b.cancel);
    }

    @Test
    void endCall_noWaitlist_acksNotFound() {
        Fake b = new Fake();
        b.actorWaitlist = null;
        var ack = new CallSocketDispatcher(b).endCall(CallSocketDispatcher.USER, "U1", "W", 0);
        assertFalse(ack.success);
        assertEquals("Waitlist not found", ack.message);
    }

    @Test
    void endCall_sessionConsultantEarly_firstTime_retriesAndDoesNotSettle() {
        Fake b = new Fake();
        b.actorWaitlist = new Document("_id", "W").append("used_for", "session")
                .append("user_id", "U1").append("consultant_id", "C1")
                .append("session_info", new Document("sessionMeta",
                        new Document("sessionTimeInMinutes", 10).append("isConsultantCancelled", false)));
        var ack = new CallSocketDispatcher(b).endCall(CallSocketDispatcher.CONSULTANT, "C1", "W", 60);
        assertFalse(ack.success);
        assertEquals("Consultant has cancelled the session", ack.message);
        assertTrue(b.sessionRetryCalled);
        assertNull(b.timeout, "no settlement on the first consultant cancel");
    }

    @Test
    void endCall_normal_settlesAndAcks() {
        Fake b = new Fake();
        b.actorWaitlist = new Document("_id", "W").append("used_for", "chat")
                .append("user_id", "U1").append("consultant_id", "C1");
        b.timeoutReturn = "SETTLED";
        var ack = new CallSocketDispatcher(b).endCall(CallSocketDispatcher.USER, "U1", "W", 120);
        assertTrue(ack.success);
        assertEquals("Call ended", ack.message);
        assertEquals("SETTLED", ack.data);
        assertEquals("W|U1|C1|ended|user", b.timeout);
    }

    @Test
    void verifyEndCall_trueWhenCompletedOrCallHashIsEmpty() {
        Fake b = new Fake();
        b.callData = Map.of("status", "completed");
        assertTrue(new CallSocketDispatcher(b).verifyEndCall("R"));
        b.callData = Map.of("status", "accepted");
        assertFalse(new CallSocketDispatcher(b).verifyEndCall("R"));
        b.callData = Map.of();
        assertTrue(new CallSocketDispatcher(b).verifyEndCall("R"));
    }

    @Test
    void checkCallStatus_completedWaitlistDeletesStaleHash() {
        Fake b = new Fake();
        b.callData = Map.of("status", "initiated");
        b.waitlistCompleted = true;
        assertTrue(new CallSocketDispatcher(b).checkCallStatus("R"));
        assertEquals("R", b.deletedCallData);
    }

    @Test
    void checkCallStatus_acceptsOnlyAcceptedRedisState() {
        Fake b = new Fake();
        b.callData = Map.of("status", "accepted");
        assertTrue(new CallSocketDispatcher(b).checkCallStatus("R"));
        b.callData = Map.of("status", "partially_accepted");
        assertFalse(new CallSocketDispatcher(b).checkCallStatus("R"));
    }

    @Test
    void endCall_exotelIsRejectedWithoutSettlement() {
        Fake b = new Fake();
        b.actorWaitlist = new Document("_id", "W").append("used_for", "private_call")
                .append("user_id", "U1").append("consultant_id", "C1")
                .append("session_info", new Document("callModeCourier", "exotel"));
        var ack = new CallSocketDispatcher(b).endCall(CallSocketDispatcher.USER, "U1", "W", 20);
        assertFalse(ack.success);
        assertEquals("Call cannot be ended!", ack.message);
        assertNull(b.timeout);
        assertEquals("call_error", b.lastEmitEvent);
    }

    @Test
    void roomRemainingTime_activeCallUsesDurationClockAndElapsedTime() {
        Fake b = new Fake();
        b.timerActive = true;
        b.remainingTime = 275;
        b.waitlistTime = new Document("timeLap", new Document("startTime", new java.util.Date(System.currentTimeMillis() - 10_000)));
        Map<String, Long> result = new CallSocketDispatcher(b).roomRemainingTime("R", "accepted");
        assertEquals(275L, result.get("time"));
        assertTrue(result.get("elapsedTime") >= 9L);
        assertEquals("R:duration", b.lastTimerKey);
    }

    @Test
    void extendCall_emitsFailedWhenNotExtended() {
        Fake b = new Fake();
        b.extendResult = false;
        new CallSocketDispatcher(b).extendCall("R", 300);
        assertEquals("extend_call_failed", b.lastEmitEvent);
    }

    static class Fake implements CallSocketBackend {
        String accept, cancel, timeout, status, lastEmitEvent, deletedCallData, lastTimerKey;
        Object timeoutReturn;
        Document progressSession, actorWaitlist, completedWaitlistTime, waitlistTime;
        Map<String, String> callData = Map.of();
        boolean waitlistCompleted;
        boolean timerActive;
        long remainingTime;
        boolean sessionRetryCalled = false;
        boolean extendResult = true;
        final List<Object> emits = new CopyOnWriteArrayList<>();

        @Override public void acceptCall(String r, String u, String c, String t) { accept = r + "|" + u + "|" + c + "|" + t; }
        @Override public void cancelCall(String r, String u, String c) { cancel = r + "|" + u + "|" + c; }
        @Override public Document findProgressSessionForConsultant(String r, String c) { return progressSession; }
        @Override public Document findProgressWaitlistForActor(String r, String ut, String a) { return actorWaitlist; }
        @Override public void hsetStatus(String r, String s) { status = s; }
        @Override public Object handleCallTimeout(String r, String u, String c, String cs, String by) { timeout = r + "|" + u + "|" + c + "|" + cs + "|" + by; return timeoutReturn; }
        @Override public void initiateSessionCallToConsultant(Document w) { sessionRetryCalled = true; }
        @Override public Map<String, String> getCallData(String r) { return callData; }
        @Override public boolean isWaitlistCompleted(String r) { return waitlistCompleted; }
        @Override public void deleteCallData(String r) { deletedCallData = r; }
        @Override public Document findCompletedWaitlistTime(String r) { return completedWaitlistTime; }
        @Override public Document findWaitlistTime(String r) { return waitlistTime; }
        @Override public boolean isTimerActive(String k) { lastTimerKey = k; return timerActive; }
        @Override public long getRemainingTime(String k) { lastTimerKey = k; return remainingTime; }
        @Override public boolean extendCallDuration(String r, long s) { return extendResult; }
        @Override public void joinRoom(String r) { }
        @Override public void leaveRoom(String r) { }
        @Override public void emit(String event, Object payload) { lastEmitEvent = event; emits.add(payload); }
    }
}
