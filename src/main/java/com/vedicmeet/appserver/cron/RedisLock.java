package com.vedicmeet.appserver.cron;

/**
 * Minimal Redis ops for the distributed lock (Node utils/classes/redis-lock.js). Behind a port so
 * {@link RedisLockService}'s acquire/skip/safe-release logic is unit-testable without Redis.
 */
public interface RedisLock {

    /** SET key token NX EX ttlSeconds — true iff the lock was acquired (not already held). */
    boolean setNxEx(String key, String token, long ttlSeconds);

    /**
     * The Node RELEASE_LOCK_SCRIPT: {@code if get(key)==token then del(key) end}. Compare-and-delete
     * so a process only ever releases the lock it still owns (never one another process re-acquired
     * after a TTL expiry). Returns true iff this call deleted the key.
     */
    boolean delIfMatches(String key, String token);
}
