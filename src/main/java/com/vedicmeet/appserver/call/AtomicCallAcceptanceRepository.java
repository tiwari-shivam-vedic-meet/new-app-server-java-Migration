package com.vedicmeet.appserver.call;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis implementation of the two-party accept state machine.
 *
 * <p>The entire decision and write run in one Lua script. This is the Redis equivalent of a
 * conditional {@code UPDATE ... WHERE status = ?} in Spring/JPA and removes the production race where
 * two concurrent accepts both read {@code initiated} and both write {@code partially_accepted}.</p>
 *
 * <p>Only call hashes explicitly created with {@code owner=java} are mutable here. That prevents a
 * split-brain migration in which Node creates a call but Java consumes its socket event.</p>
 */
@Repository
public class AtomicCallAcceptanceRepository {

    private static final DefaultRedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return 'GHOST' end
            local owner = redis.call('HGET', KEYS[1], 'owner')
            if owner ~= 'java' then return 'INVALID_STATE' end

            if status == 'initiated' then
              redis.call('HSET', KEYS[1],
                'status', 'partially_accepted',
                'firstAcceptedBy', ARGV[1],
                'firstAcceptTime', ARGV[3])
              return 'FIRST'
            end

            if status == 'partially_accepted' then
              if ARGV[2] == '1' then
                redis.call('HSET', KEYS[1], 'status', 'accepted', 'startTime', ARGV[3])
                return 'RECONNECTED'
              end
              local first = redis.call('HGET', KEYS[1], 'firstAcceptedBy')
              if first == ARGV[1] then return 'SAME_ACCEPTOR' end
              redis.call('HSET', KEYS[1],
                'status', 'accepted',
                'startTime', ARGV[3],
                'secondAcceptedBy', ARGV[1])
              return 'SECOND'
            end

            if status == 'accepted' then return 'ALREADY_ACCEPTED' end
            return 'INVALID_STATE'
            """, String.class);

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            local second = redis.call('HGET', KEYS[1], 'secondAcceptedBy')
            if status == 'accepted' and second == ARGV[1] then
              redis.call('HSET', KEYS[1], 'status', 'partially_accepted')
              redis.call('HDEL', KEYS[1], 'startTime', 'secondAcceptedBy')
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public AtomicCallAcceptanceRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public CallAcceptStore.AcceptanceClaim claim(String roomId, String acceptorId,
                                                  boolean reconnect, long acceptedAtMillis) {
        String result = redis.execute(CLAIM_SCRIPT, List.of(roomId), acceptorId,
                reconnect ? "1" : "0", String.valueOf(acceptedAtMillis));
        CallAcceptStore.ClaimType type;
        try {
            type = result == null ? CallAcceptStore.ClaimType.INVALID_STATE
                    : CallAcceptStore.ClaimType.valueOf(result);
        } catch (IllegalArgumentException unexpectedResult) {
            type = CallAcceptStore.ClaimType.INVALID_STATE;
        }

        Map<Object, Object> raw = redis.opsForHash().entries(roomId);
        Map<String, String> callData = new LinkedHashMap<>();
        raw.forEach((key, value) -> callData.put(String.valueOf(key), String.valueOf(value)));
        return new CallAcceptStore.AcceptanceClaim(type, callData);
    }

    public void rollbackSecondAcceptance(String roomId, String secondAcceptorId) {
        redis.execute(ROLLBACK_SCRIPT, List.of(roomId), secondAcceptorId);
    }
}
