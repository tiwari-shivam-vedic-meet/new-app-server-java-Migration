package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Pins {@link CallCancelService}, including cancellation of both production timer keys. */
class CallCancelServiceTest {

    @Test
    void nullHash_isNoData() {
        Fake s = new Fake();
        s.hash = null;
        assertEquals(CallCancelService.CancelOutcome.NO_DATA, new CallCancelService(s).cancelCall("R", "U", null));
    }

    @Test
    void bothIds_callerIdUndefined_fallsToConsultant_andCancelsBothTimers() {
        Fake s = new Fake();
        s.hash = hash("U", "C");
        var out = new CallCancelService(s).cancelCall("R", "U", "C");
        assertEquals(CallCancelService.CancelOutcome.CANCELLED, out);
        assertTrue(s.statusCancelled);
        assertEquals("R", s.canceledMissedRoomId);
        assertEquals("R", s.canceledDurationRoomId);
        assertEquals("consultant", s.lastCancelledBy, "no callerId in hash -> consultant");
        assertEquals(2, countEvent(s, "call_canceled"));
    }

    @Test
    void onlyConsultant_marksConsultantCancel_andEmitsAppAction() {
        Fake s = new Fake();
        s.hash = hash("U", "C");
        new CallCancelService(s).cancelCall("R", null, "C");
        assertTrue(s.consultantCancelledCalled);
        assertEquals(1, countEvent(s, "app_action"));
    }

    @Test
    void onlyUser_userCancel_notifiesConsultantWhenFcmPresent() {
        Fake s = new Fake();
        s.hash = hash("U", "C");
        s.consultant = new Document("device", new Document("fcmToken", List.of("tok1")));
        new CallCancelService(s).cancelCall("R", "U", null);
        assertTrue(s.userMissedNotified);
    }

    @Test
    void consultantCancel_firstPurchaseCoupon_callsNextConsultant() {
        Fake s = new Fake();
        s.hash = hash("U", "C");
        s.updated = new Document("coupon", new Document("type", "first_purchase"));
        s.next = new CallCancelService.NextCaller();
        new CallCancelService(s).cancelCall("R", null, "C");
        assertTrue(s.nextScheduled);
        assertEquals(1, countEvent(s, "call_missed_calling_next_consultant"));
    }

    private static Map<String, String> hash(String u, String c) {
        Map<String, String> h = new HashMap<>();
        h.put("userId", u);
        h.put("consultantId", c);
        h.put("status", "initiated");
        return h;
    }

    private static int countEvent(Fake s, String event) {
        return (int) s.emits.stream().filter(e -> e[1].equals(event)).count();
    }

    static class Fake implements CallCancelStore {
        Map<String, String> hash;
        Document consultant, user, updated;
        CallCancelService.NextCaller next;
        boolean statusCancelled, consultantCancelledCalled, userMissedNotified, nextScheduled;
        String canceledMissedRoomId, canceledDurationRoomId, lastCancelledBy;
        final List<Object[]> emits = new CopyOnWriteArrayList<>();

        @Override public Map<String, String> getCallHash(String r) { return hash; }
        @Override public void setStatusCancelled(String r) { statusCancelled = true; }
        @Override public void cancelMissedTimer(String r) { canceledMissedRoomId = r; }
        @Override public void cancelDurationTimer(String r) { canceledDurationRoomId = r; }
        @Override public void consultantCancelledCall(String c, String u, String r) { consultantCancelledCalled = true; }
        @Override public void emit(String room, String event, Object payload) {
            emits.add(new Object[]{room, event, payload});
            if (event.equals("call_canceled") && payload instanceof Map) lastCancelledBy = String.valueOf(((Map<?, ?>) payload).get("cancelledBy"));
        }
        @Override public Document pushCancelledLog(String r, String by) { return updated; }
        @Override public Document findConsultant(String id) { return consultant; }
        @Override public Document findUser(String id) { return user; }
        @Override public void notifyUserMissedCall(Document c, String userName) { userMissedNotified = true; }
        @Override public CallCancelService.NextCaller callToNextConsultantAsItFree(String r) { return next; }
        @Override public void scheduleInitiateCall(CallCancelService.NextCaller n) { nextScheduled = true; }
    }
}
