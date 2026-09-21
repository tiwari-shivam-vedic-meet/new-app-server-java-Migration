package com.vedicmeet.appserver.call;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the safe billing-clock behavior of {@link CallExtendService}. */
class CallExtendServiceTest {

    @Test
    void accepted_extendSucceeds_emitsToBoth_usesDurationTimerKey() {
        Fake s = new Fake();
        s.hash = new HashMap<>();
        s.hash.put("status", "accepted");
        s.hash.put("userId", "U");
        s.hash.put("consultantId", "C");
        s.extendOk = true;
        s.ttl = 120;

        assertTrue(new CallExtendService(s).extendCall("R", 300));
        assertEquals("R:duration", s.extendedKey, "must extend the live billing timer");
        assertEquals(2, s.emits.size());
        assertTrue(s.emits.stream().allMatch(e -> e[1].equals("call_extended")));
    }

    @Test
    void notAccepted_returnsFalse() {
        Fake s = new Fake();
        s.hash = new HashMap<>();
        s.hash.put("status", "initiated");
        assertFalse(new CallExtendService(s).extendCall("R", 300));
        assertNull(s.extendedKey);
    }

    @Test
    void extendFails_returnsFalse() {
        Fake s = new Fake();
        s.hash = new HashMap<>();
        s.hash.put("status", "accepted");
        s.extendOk = false;
        assertFalse(new CallExtendService(s).extendCall("R", 300));
        assertEquals(0, s.emits.size());
    }

    static class Fake implements CallExtendStore {
        Map<String, String> hash;
        boolean extendOk;
        long ttl;
        String extendedKey;
        final List<Object[]> emits = new CopyOnWriteArrayList<>();

        @Override public Map<String, String> getCallHash(String roomId) { return hash; }
        @Override public boolean extendTimer(String key, long s) { extendedKey = key; return extendOk; }
        @Override public long ttlSeconds(String key) { return ttl; }
        @Override public void emit(String room, String event, Object payload) { emits.add(new Object[]{room, event, payload}); }
    }
}
