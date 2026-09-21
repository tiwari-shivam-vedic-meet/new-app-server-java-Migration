package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful {@link CallOpenService} (Node openCall L28-165). */
class CallOpenServiceTest {

    @Test
    void busy_throwsConsultantIsBusy() {
        Fake s = new Fake();
        s.busy = new Document("_id", "W");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new CallOpenService(s).openCall("user", "U", "C", "R", "chat"));
        assertEquals("Consultant is busy", ex.getMessage());
        assertFalse(s.hashWritten, "no hash written when busy");
    }

    @Test
    void happyPath_writesInitiatedHash_createsCallInitiated_sendsFcmToBothParties_registersMissedTimer() {
        Fake s = new Fake();
        s.user = new Document("name", "Asha").append("userId", "u123")
                .append("device", new Document("fcmToken", List.of("ut1", "ut2")).append("voipToken", "uvoip"));
        s.consultant = new Document("accountName", "Guru Ji")
                .append("device", new Document("fcmToken", List.of("ct1")));

        Map<String, String> out = new CallOpenService(s).openCall("user", "U", "C", "R", "audio");

        assertTrue(s.hashWritten);
        assertEquals("initiated", s.writtenHash.get("status"));
        assertEquals(4 * 60 * 60, s.ttl, "current production keeps the call hash for four hours");
        assertEquals("java", s.writtenHash.get("owner"));
        assertTrue(s.callInitiatedCreated);
        // FCM: 2 user tokens + 1 consultant token
        assertEquals(3, s.fcm.size());
        assertEquals("Guru Ji is calling you", s.fcm.get(0)[2], "user is told the consultant name");
        assertTrue(s.fcm.stream().anyMatch(f -> f[3].equals("cons") && f[2].equals("Asha is calling you")));
        assertEquals(1, s.voipCount, "only the user had a voip token");
        assertTrue(s.missedTimerRegistered);
        assertEquals("initiated", out.get("status"));
    }

    @Test
    void missingNames_fallBackToDefaults() {
        Fake s = new Fake();
        s.user = new Document("device", new Document("fcmToken", List.of("ut1")));
        s.consultant = new Document("device", new Document("fcmToken", List.of("ct1")));
        new CallOpenService(s).openCall("system", "U", "C", "R", "chat");
        assertTrue(s.fcm.stream().anyMatch(f -> f[2].equals("Consultant is calling you")));
        assertTrue(s.fcm.stream().anyMatch(f -> f[2].equals("User is calling you")));
    }

    static class Fake implements CallOpenStore {
        Document busy, user, consultant;
        boolean hashWritten, callInitiatedCreated, missedTimerRegistered;
        long ttl;
        int voipCount;
        Map<String, Object> writtenHash;
        final List<Object[]> fcm = new CopyOnWriteArrayList<>();

        @Override public void sleep(long ms) { }
        @Override public Document findBusyWaitlist(String u, String c) { return busy; }
        @Override public void deleteCallHash(String r) { }
        @Override public void writeCallHash(String r, Map<String, Object> hash, long ttlSeconds) { hashWritten = true; writtenHash = hash; ttl = ttlSeconds; }
        @Override public Document findUserForCall(String u) { return user; }
        @Override public Document findConsultantForCall(String c) { return consultant; }
        @Override public void createCallInitated(String u, String c, Map<String, Object> up, Map<String, Object> cp) { callInitiatedCreated = true; }
        @Override public void sendFcm(String token, String title, String body, String userType, Map<String, Object> data) { fcm.add(new Object[]{token, title, body, userType}); }
        @Override public void sendVoip(String v, String ut, String t, String b, String n, Map<String, Object> p) { voipCount++; }
        @Override public void pushInitiatedLog(String r, String callBy) { }
        @Override public void registerMissedTimer(String r, long s) { missedTimerRegistered = true; }
        @Override public Map<String, String> readCallHash(String r) { Map<String, String> m = new HashMap<>(); m.put("status", "initiated"); return m; }
    }
}
