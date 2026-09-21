package com.vedicmeet.appserver.call;

import org.springframework.stereotype.Service;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. Faithful port of the Redis billing-clock WRITE ops in Node
 * utils/queues/timer-queue.js — the counterpart to Prompt B's read-side `discovery/TimerReadService`.
 * The billing key is {@code "<roomId>:duration"}; it is registered when a call goes to progress,
 * extended on recharge, and stopped at call end (which triggers settlement).
 *
 * Node parity:
 *   - registerTimer  → SETEX(key, seconds, 'pending').
 *   - extendTimer    → ttl = TTL(key); if ttl <= 0 return false; EXPIRE(key, ttl + additional).
 *   - getRemainingTime → EXISTS ? TTL : -2.
 *
 * NOT wired. The BullMQ 'timer-fired' job that Node schedules alongside these (to fire the end-of-call
 * handler on a worker) is intentionally left on Node per COPILOT_HANDOFF.md Prompt F ("keep the queues
 * on Node initially"); wiring a Java worker to the same Redis keys/job names is a later step.
 */
@Service
public class TimerWriteService {

    private final RedisTimerClock clock;

    public TimerWriteService(RedisTimerClock clock) {
        this.clock = clock;
    }

    /** Start the billing clock (SETEX key seconds 'pending'). */
    public boolean registerTimer(String key, long seconds) {
        clock.setPending(key, seconds);
        return true;
    }

    /** Extend an ACTIVE timer by additionalSeconds. No-op (false) if the timer already expired. */
    public boolean extendTimer(String key, long additionalSeconds) {
        long ttl = clock.getTtl(key);
        if (ttl <= 0) return false;
        clock.expire(key, ttl + additionalSeconds);
        return true;
    }

    /** Stop the billing clock (DEL key). */
    public boolean stopTimer(String key) {
        clock.delete(key);
        return true;
    }

    /** Remaining billing seconds; -2 when the timer is not running (matches Node getRemainingTime). */
    public long getRemainingTime(String key) {
        return clock.exists(key) ? clock.getTtl(key) : -2;
    }
}
