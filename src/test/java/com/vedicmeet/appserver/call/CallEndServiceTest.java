package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful {@link CallEndService} endCall sequence (Node call.js L1224). Real-time / shadow. */
class CallEndServiceTest {

    @Test
    void endCall_stopsTimer_marksEnded_emitsBoth_andNotifiesConsultant() {
        FakeStore s = new FakeStore();
        s.hash = new LinkedHashMap<>();               // {} — truthy in Node, proceeds
        s.waitlist = new Document("_id", "W1").append("threadId", "t1");

        CallEndService.EndOutcome out = new CallEndService(s).endCall("R", "U", "C");

        assertEquals(CallEndService.EndOutcome.ENDED, out);
        assertTrue(s.timerCanceled);
        assertEquals("ended", s.status);
        // call_ended to user+consultant AND leave_call_room to user+consultant = 4 emits
        assertEquals(4, s.emits.size());
        assertTrue(s.emits.stream().anyMatch(e -> e[1].equals("call_ended") && e[0].equals("U")));
        assertTrue(s.emits.stream().anyMatch(e -> e[1].equals("leave_call_room") && e[0].equals("C")));
        assertTrue(s.consultantNotified);
    }

    @Test
    void endCall_withoutConsultant_skipsConsultantNotification() {
        FakeStore s = new FakeStore();
        s.hash = new LinkedHashMap<>();
        s.waitlist = new Document("_id", "W1").append("threadId", "t1");
        new CallEndService(s).endCall("R", "U", null);
        assertFalse(s.consultantNotified);
    }

    @Test
    void endCall_nullHash_isNoData() {
        FakeStore s = new FakeStore();
        s.hash = null; // only a genuinely null hash short-circuits
        assertEquals(CallEndService.EndOutcome.NO_DATA, new CallEndService(s).endCall("R", "U", "C"));
        assertFalse(s.timerCanceled);
    }

    static class FakeStore implements CallEndStore {
        Map<String, String> hash;
        Document waitlist;
        boolean timerCanceled = false;
        String status;
        boolean consultantNotified = false;
        final List<Object[]> emits = new CopyOnWriteArrayList<>();

        @Override public Map<String, String> getCallHash(String roomId) { return hash; }
        @Override public void cancelDurationTimer(String roomId) { timerCanceled = true; }
        @Override public void setStatus(String roomId, String s) { status = s; }
        @Override public Document pushEndedLog(String roomId) { return waitlist; }
        @Override public void emit(String room, String event, Object payload) { emits.add(new Object[]{room, event, payload}); }
        @Override public void notifyConsultantCallEnded(String consultantId) { consultantNotified = true; }
    }
}
