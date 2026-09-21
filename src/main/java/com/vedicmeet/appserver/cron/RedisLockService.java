package com.vedicmeet.appserver.cron;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * ⚠ SHADOW-ONLY infra for Prompt F. Faithful port of Node {@code withRedisLock}/{@code withCronLock}
 * (utils/classes/redis-lock.js): run a task only if THIS process wins the Redis lock; other processes
 * skip. This is the safety primitive that stops a scheduled job double-running across instances.
 *
 * Node parity:
 *   - token = "<pid>:<random hex>"; acquire = SET NX EX; on miss → {ran:false, skipped:true}.
 *   - always release in a finally via the compare-and-delete script (never deletes someone else's lock).
 *   - withCronLock(jobName) locks key "cron:lock:<jobName>".
 *
 * The @Scheduled triggers + the cron task bodies (Interakt/CleverTap re-engagement) and BullMQ/Socket.IO
 * remain external (kept on Node per COPILOT_HANDOFF.md Prompt F); this is the reusable lock they need.
 */
@Service
public class RedisLockService {

    private static final long DEFAULT_TTL_SECONDS = 3600;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisLock redis;

    public RedisLockService(RedisLock redis) {
        this.redis = redis;
    }

    public static final class LockResult<T> {
        public final boolean ran;
        public final boolean skipped;
        public final T result;

        private LockResult(boolean ran, boolean skipped, T result) {
            this.ran = ran;
            this.skipped = skipped;
            this.result = result;
        }

        static <T> LockResult<T> ran(T result) { return new LockResult<>(true, false, result); }
        static <T> LockResult<T> skipped() { return new LockResult<>(false, true, null); }
    }

    public <T> LockResult<T> withRedisLock(String lockKey, Supplier<T> fn, long ttlSeconds) {
        String token = pid() + ":" + randomHex();

        boolean acquired;
        try {
            acquired = redis.setNxEx(lockKey, token, ttlSeconds);
        } catch (Exception e) {
            return LockResult.skipped();
        }
        if (!acquired) return LockResult.skipped();

        try {
            return LockResult.ran(fn.get());
        } finally {
            try {
                redis.delIfMatches(lockKey, token);
            } catch (Exception ignore) {
                // Node logs + swallows release failures; the TTL will reclaim the lock.
            }
        }
    }

    /** Run a cron task under the lock "cron:lock:&lt;jobName&gt;". */
    public LockResult<Void> withCronLock(String jobName, Runnable task, long ttlSeconds) {
        long ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        return withRedisLock("cron:lock:" + jobName, () -> { task.run(); return null; }, ttl);
    }

    private String pid() {
        try {
            return String.valueOf(ProcessHandle.current().pid());
        } catch (Throwable t) {
            return "0";
        }
    }

    private String randomHex() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
