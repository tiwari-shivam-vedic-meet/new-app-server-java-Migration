package com.vedicmeet.appserver.cron;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;

/**
 * Production {@link RedisLock} over {@link StringRedisTemplate}. SHADOW-ONLY (reached only via
 * {@link RedisLockService}). The release uses the exact Node Lua compare-and-delete script.
 */
@Repository
public class RedisLockImpl implements RedisLock {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLockImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean setNxEx(String key, String token, long ttlSeconds) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean delIfMatches(String key, String token) {
        Long deleted = redis.execute(RELEASE, Collections.singletonList(key), token);
        return deleted != null && deleted > 0;
    }
}
