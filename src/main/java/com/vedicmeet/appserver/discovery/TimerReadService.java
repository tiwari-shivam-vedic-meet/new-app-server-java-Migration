package com.vedicmeet.appserver.discovery;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * READ-ONLY port of the Node timer helpers used by the discovery reads
 * (utils/queues/timer-queue.js getRemainingTime / isTimerActive). These only
 * inspect the Redis billing-clock TTL — they never write it — so they are safe to
 * use from the read path. The full timer WRITE machinery stays out of scope
 * (Phase 2.4/2.5).
 *
 * Node semantics preserved exactly:
 *   getRemainingTime(key): key missing -> -2; else redis.ttl(key); on error -> -1.
 *   isTimerActive(key):    key missing -> false; else redis.ttl(key) > 0; on error -> false.
 */
@Service
public class TimerReadService {

    private final StringRedisTemplate redis;

    public TimerReadService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long getRemainingTime(String key) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                return -2;
            }
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            return ttl == null ? -1 : ttl;
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isTimerActive(String key) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                return false;
            }
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            return ttl != null && ttl > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
