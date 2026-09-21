package com.vedicmeet.appserver.call;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link RedisTimerClock} over {@link StringRedisTemplate} (same client the read-side
 * {@code discovery/TimerReadService} uses). SHADOW-ONLY (only reached via {@link TimerWriteService}).
 */
@Repository
public class RedisTimerClockImpl implements RedisTimerClock {

    private final StringRedisTemplate redis;

    public RedisTimerClockImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void setPending(String key, long seconds) {
        redis.opsForValue().set(key, "pending", Duration.ofSeconds(seconds));
    }

    @Override
    public long getTtl(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return ttl == null ? -2 : ttl;
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    @Override
    public void expire(String key, long seconds) {
        redis.expire(key, Duration.ofSeconds(seconds));
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }
}
