package com.vedicmeet.appserver.call;

/**
 * Minimal Redis clock operations the call billing-timer needs (Node utils/queues/timer-queue.js).
 * Behind a port so {@link TimerWriteService}'s extend/TTL arithmetic is unit-testable without Redis.
 * Semantics match ioredis: {@link #getTtl} returns -2 when the key is missing, -1 when it has no expiry.
 */
public interface RedisTimerClock {

    /** SETEX key seconds 'pending'. */
    void setPending(String key, long seconds);

    /** TTL key — remaining seconds, or -2 (missing) / -1 (no expiry). */
    long getTtl(String key);

    /** EXISTS key. */
    boolean exists(String key);

    /** EXPIRE key seconds. */
    void expire(String key, long seconds);

    /** DEL key. */
    void delete(String key);
}
