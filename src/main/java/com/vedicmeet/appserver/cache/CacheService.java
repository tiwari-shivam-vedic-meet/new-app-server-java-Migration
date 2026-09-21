package com.vedicmeet.appserver.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Port of the Node CacheService (utils/classes/cache.js). Values are stored as JSON
 * strings on the SAME Redis the Node service uses, so a key written by either service
 * is readable by the other — essential during canary, when the gateway may route the
 * same path to Node or Java on different requests.
 *
 * Semantics kept identical:
 *   - get: JSON.parse(redis.get) or null; NEVER throws (fail-open, returns null).
 *   - set: redis.setex(key, ttlSeconds, JSON.stringify(data)); fail-open.
 *   - invalidate: SCAN (not KEYS) for the pattern, then DEL — non-blocking.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public CacheService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** Returns the cached value deserialized to the given type, or null on miss/error. */
    public <T> T get(String key, Class<T> type) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached == null) {
                return null;
            }
            return mapper.readValue(cached, type);
        } catch (Exception e) {
            log.warn("[Cache] error getting key {}: {}", key, e.getMessage());
            return null; // fail gracefully, exactly like Node
        }
    }

    /** Stores data as JSON with a TTL. Returns false on error (never throws). */
    public boolean set(String key, Object data, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(data), Duration.ofSeconds(ttlSeconds));
            return true;
        } catch (Exception e) {
            log.warn("[Cache] error setting key {}: {}", key, e.getMessage());
            return false;
        }
    }

    /** Deletes a single key, or all keys matching a pattern when it contains '*'. */
    public boolean invalidate(String pattern) {
        try {
            if (pattern.contains("*")) {
                List<String> keys = scanKeys(pattern);
                if (!keys.isEmpty()) {
                    redis.delete(keys);
                }
            } else {
                redis.delete(pattern);
            }
            return true;
        } catch (Exception e) {
            log.warn("[Cache] error invalidating {}: {}", pattern, e.getMessage());
            return false;
        }
    }

    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }
}
