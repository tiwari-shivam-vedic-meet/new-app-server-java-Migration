package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two-phase acceptCall contract for {@link CallAcceptService} (Node call.js L1494), migrated as-is.
 * A fake {@link CallAcceptStore} captures redis/waitlist/timer/emit effects so each transition is
 * pinned without Redis/Socket.IO. Shadow-only / real-time gate.
 */
class CallAcceptServiceTest {

    @Test
    void emptyRedis_isGhostNoOp() {
        FakeStore s = new FakeStore();
        assertEquals(CallAcceptService.AcceptOutcome.GHOST_NO_REDIS,
                new CallAcceptService(s).acceptCall("R", "U", null, null));
        assertEquals(0, s.hashWrites.size());
    }

    @Test
    void initiated_firstAccept_goesPartiallyAccepted_emitsAndLogs() {
        FakeStore s = new FakeStore();
        s.hash.put("status", "initiated");
        s.hash.put("userId", "U");
        s.hash.put("consultantId", "C");

        CallAcceptService.AcceptOutcome out = new CallAcceptService(s).acceptCall("R", "U", null, null);

        assertEquals(CallAcceptService.AcceptOutcome.PARTIALLY_ACCEPTED, out);
        assertEquals("partially_accepted", s.hashWrites.get(0).get("status"));
        assertEquals("U", s.hashWrites.get(0).get("firstAcceptedBy"));
        assertEquals(2, s.emits.size(), "emit call_partially_accepted to user + consultant");
        assertEquals("partially accepted by", s.lastLogStatus);
    }

    @Test
    void partiallyAccepted_sessionConnectAgain_reconnects() {
        FakeStore s = new FakeStore();
        s.hash.put("status", "partially_accepted");
        s.hash.put("firstAcceptedBy", "U");
        CallAcceptService.AcceptOutcome out =
                new CallAcceptService(s).acceptCall("R", null, "C", "session_connect_again");
        assertEquals(CallAcceptService.AcceptOutcome.RECONNECTED, out);
        assertEquals("accepted", s.hashWrites.get(0).get("status"));
    }

    @Test
    void partiallyAccepted_differentAcceptor_startsProgress_andBillingClock() {
        FakeStore s = new FakeStore();
        s.hash.put("status", "partially_accepted");
        s.hash.put("firstAcceptedBy", "U");
        s.hash.put("userId", "U");
        s.hash.put("consultantId", "C");
        s.progressEntry = new Document("_id", "R").append("requested_time", 600).append("threadId", "t1")
                .append("session_info", new Document("mode", "chat"));

        CallAcceptService.AcceptOutcome out = new CallAcceptService(s).acceptCall("R", null, "C", null);

        assertEquals(CallAcceptService.AcceptOutcome.PROGRESS_STARTED, out);
        assertTrue(s.missedTimerCanceled);
        assertEquals("accepted", s.hashWrites.get(0).get("status"));
        assertTrue(s.progressed);
        assertEquals(600L, s.durationTimerSeconds, "billing clock started for requested_time");
        assertEquals(1, s.joinEmits.size(), "join_call_room emitted to the other party");
    }

    @Test
    void partiallyAccepted_sameAcceptor_isNoOp() {
        FakeStore s = new FakeStore();
        s.hash.put("status", "partially_accepted");
        s.hash.put("firstAcceptedBy", "U");
        s.hash.put("userId", "U");
        CallAcceptService.AcceptOutcome out = new CallAcceptService(s).acceptCall("R", "U", null, null);
        assertEquals(CallAcceptService.AcceptOutcome.NOOP_SAME_ACCEPTOR, out);
        assertEquals(-1L, s.durationTimerSeconds, "no billing clock on same-acceptor re-accept");
    }

    @Test
    void secondAccept_whenWaitlistCannotProgress_compensatesRedisClaim() {
        FakeStore s = new FakeStore();
        s.hash.put("status", "partially_accepted");
        s.hash.put("firstAcceptedBy", "U");
        s.hash.put("userId", "U");
        s.hash.put("consultantId", "C");

        CallAcceptService.AcceptOutcome out = new CallAcceptService(s).acceptCall("R", null, "C", null);

        assertEquals(CallAcceptService.AcceptOutcome.STATE_CONFLICT, out);
        assertTrue(s.rolledBack);
        assertEquals(-1L, s.durationTimerSeconds);
    }

    @Test
    void accepted_reJoin_returnsAlreadyAccepted() {
        FakeStore s = new FakeStore();
        s.hash.put("status", "accepted");
        s.hash.put("userId", "U");
        s.hash.put("consultantId", "C");
        s.waitlist = new Document("_id", "R").append("threadId", "t1")
                .append("session_info", new Document("mode", "chat"));
        CallAcceptService.AcceptOutcome out = new CallAcceptService(s).acceptCall("R", "U", null, null);
        assertEquals(CallAcceptService.AcceptOutcome.ALREADY_ACCEPTED, out);
        assertEquals(1, s.joinEmits.size());
    }

    static class FakeStore implements CallAcceptStore {
        final Map<String, String> hash = new LinkedHashMap<>();
        final List<Map<String, Object>> hashWrites = new CopyOnWriteArrayList<>();
        final List<Object[]> emits = new CopyOnWriteArrayList<>();
        final List<Object[]> joinEmits = new CopyOnWriteArrayList<>();
        String lastLogStatus;
        boolean missedTimerCanceled = false;
        boolean progressed = false;
        boolean rolledBack = false;
        long durationTimerSeconds = -1;
        Document progressEntry;
        Document waitlist;

        @Override public AcceptanceClaim claimAcceptance(String roomId, String acceptorId,
                                                         boolean reconnect, long acceptedAtMillis) {
            String status = hash.get("status");
            if (status == null) return new AcceptanceClaim(ClaimType.GHOST, hash);
            if ("initiated".equals(status)) {
                Map<String, Object> write = new LinkedHashMap<>();
                write.put("status", "partially_accepted");
                write.put("firstAcceptedBy", acceptorId);
                write.put("firstAcceptTime", acceptedAtMillis);
                hashWrites.add(write);
                hash.put("status", "partially_accepted");
                hash.put("firstAcceptedBy", acceptorId);
                return new AcceptanceClaim(ClaimType.FIRST, hash);
            }
            if ("partially_accepted".equals(status)) {
                if (reconnect) {
                    Map<String, Object> write = Map.of("status", "accepted", "startTime", acceptedAtMillis);
                    hashWrites.add(write);
                    hash.put("status", "accepted");
                    return new AcceptanceClaim(ClaimType.RECONNECTED, hash);
                }
                if (acceptorId.equals(hash.get("firstAcceptedBy"))) {
                    return new AcceptanceClaim(ClaimType.SAME_ACCEPTOR, hash);
                }
                Map<String, Object> write = Map.of("status", "accepted", "startTime", acceptedAtMillis);
                hashWrites.add(write);
                hash.put("status", "accepted");
                return new AcceptanceClaim(ClaimType.SECOND, hash);
            }
            if ("accepted".equals(status)) return new AcceptanceClaim(ClaimType.ALREADY_ACCEPTED, hash);
            return new AcceptanceClaim(ClaimType.INVALID_STATE, hash);
        }
        @Override public void markAccepted(String userId, String consultantId) { }
        @Override public void emit(String room, String event, Object payload) { emits.add(new Object[]{room, event, payload}); }
        @Override public void pushWaitlistLog(String roomId, String actionBy, String callStatus) { lastLogStatus = callStatus; }
        @Override public void cancelMissedTimer(String roomId) { missedTimerCanceled = true; }
        @Override public Document progressWaitlist(String roomId) { progressed = true; return progressEntry; }
        @Override public void startDurationTimer(String roomId, long durationSeconds) { durationTimerSeconds = durationSeconds; }
        @Override public Document findWaitlist(String roomId) { return waitlist; }
        @Override public void emitJoinCallRoom(String toRoom, Map<String, Object> payload) { joinEmits.add(new Object[]{toRoom, payload}); }
        @Override public void rollbackSecondAcceptance(String roomId, String secondAcceptorId) { rolledBack = true; }
    }
}
