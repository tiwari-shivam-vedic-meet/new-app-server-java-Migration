package com.vedicmeet.appserver.call;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Billing-clock write contract for {@link TimerWriteService} (Node timer-queue.js). Uses an in-memory
 * {@link FakeClock} so the TTL/extend arithmetic and the "extend only while active" rule are pinned
 * without Redis. Shadow-only / real-time gate.
 */
class TimerWriteServiceTest {

    @Test
    void registerTimer_setsTtl() {
        FakeClock c = new FakeClock();
        TimerWriteService svc = new TimerWriteService(c);

        assertTrue(svc.registerTimer("R:duration", 600));
        assertEquals(600, svc.getRemainingTime("R:duration"));
    }

    @Test
    void extendTimer_addsToRemaining_whenActive() {
        FakeClock c = new FakeClock();
        c.ttl.put("R:duration", 100L);
        TimerWriteService svc = new TimerWriteService(c);

        assertTrue(svc.extendTimer("R:duration", 50));
        assertEquals(150, svc.getRemainingTime("R:duration"));
    }

    @Test
    void extendTimer_isNoOp_whenExpiredOrMissing() {
        FakeClock c = new FakeClock();
        TimerWriteService svc = new TimerWriteService(c);

        assertFalse(svc.extendTimer("R:duration", 50), "cannot extend a timer that isn't running");
        assertEquals(-2, svc.getRemainingTime("R:duration"));
    }

    @Test
    void stopTimer_clearsTheClock() {
        FakeClock c = new FakeClock();
        c.ttl.put("R:duration", 100L);
        TimerWriteService svc = new TimerWriteService(c);

        assertTrue(svc.stopTimer("R:duration"));
        assertEquals(-2, svc.getRemainingTime("R:duration"), "stopped timer reads -2 like Node");
    }

    static class FakeClock implements RedisTimerClock {
        final Map<String, Long> ttl = new LinkedHashMap<>();
        @Override public void setPending(String key, long seconds) { ttl.put(key, seconds); }
        @Override public long getTtl(String key) { return ttl.containsKey(key) ? ttl.get(key) : -2; }
        @Override public boolean exists(String key) { return ttl.containsKey(key); }
        @Override public void expire(String key, long seconds) { if (ttl.containsKey(key)) ttl.put(key, seconds); }
        @Override public void delete(String key) { ttl.remove(key); }
    }
}
