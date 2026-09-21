package com.vedicmeet.appserver.cron;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Distributed-lock contract for {@link RedisLockService} (Node withRedisLock/withCronLock). A fake
 * {@link RedisLock} emulates SET NX EX + compare-and-delete so "only the winner runs", "holders skip",
 * "always release your own token", and "release even on exception" are pinned without Redis.
 * Shadow-only infra for Prompt F.
 */
class RedisLockServiceTest {

    @Test
    void winner_runsTask_thenReleasesOwnToken() {
        FakeRedisLock r = new FakeRedisLock();
        RedisLockService svc = new RedisLockService(r);
        AtomicInteger ran = new AtomicInteger();

        RedisLockService.LockResult<Void> res = svc.withCronLock("nightly", ran::incrementAndGet, 60);

        assertTrue(res.ran);
        assertFalse(res.skipped);
        assertEquals(1, ran.get());
        assertFalse(r.held.containsKey("cron:lock:nightly"), "lock released after the task");
        assertEquals("cron:lock:nightly", r.lastKey);
    }

    @Test
    void whenLockHeld_taskIsSkipped() {
        FakeRedisLock r = new FakeRedisLock();
        r.held.put("cron:lock:nightly", "someone-else"); // already locked
        RedisLockService svc = new RedisLockService(r);
        AtomicInteger ran = new AtomicInteger();

        RedisLockService.LockResult<Void> res = svc.withCronLock("nightly", ran::incrementAndGet, 60);

        assertTrue(res.skipped);
        assertFalse(res.ran);
        assertEquals(0, ran.get(), "second holder must not run the cron");
        assertEquals("someone-else", r.held.get("cron:lock:nightly"), "existing lock untouched");
    }

    @Test
    void releaseHappensEvenIfTaskThrows() {
        FakeRedisLock r = new FakeRedisLock();
        RedisLockService svc = new RedisLockService(r);

        assertThrows(RuntimeException.class, () -> svc.withCronLock("boom", () -> {
            throw new RuntimeException("kaboom");
        }, 60));
        assertFalse(r.held.containsKey("cron:lock:boom"), "lock released in finally even on error");
    }

    @Test
    void withRedisLock_returnsResult() {
        FakeRedisLock r = new FakeRedisLock();
        RedisLockService svc = new RedisLockService(r);

        RedisLockService.LockResult<String> res = svc.withRedisLock("k", () -> "done", 30);

        assertTrue(res.ran);
        assertEquals("done", res.result);
    }

    /** Emulates SET NX EX (setNxEx) + compare-and-delete (delIfMatches). */
    static class FakeRedisLock implements RedisLock {
        final Map<String, String> held = new LinkedHashMap<>();
        String lastKey;

        @Override public boolean setNxEx(String key, String token, long ttlSeconds) {
            lastKey = key;
            if (held.containsKey(key)) return false;
            held.put(key, token);
            return true;
        }

        @Override public boolean delIfMatches(String key, String token) {
            if (token.equals(held.get(key))) { held.remove(key); return true; }
            return false;
        }
    }
}
