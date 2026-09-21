package com.vedicmeet.appserver.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Redis-backed, 50-message live-event chat matching Node ChatBox. */
@Service
public class LiveEventChatService {

    static final String PREFIX = "live_event:chat:";
    static final int MAX_MESSAGES = 50;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public LiveEventChatService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Document addMessage(String eventId, String message, String name, String userId) {
        required(eventId, "EVENT_ID_REQUIRED");
        required(message, "MESSAGE_REQUIRED");
        Document value = new Document("id", UUID.randomUUID().toString())
                .append("message", message).append("name", name)
                .append("timestamp", new Date()).append("userId", userId)
                .append("eventId", eventId);
        try {
            Map<String, Object> wire = mapper.convertValue(value, new TypeReference<>() {});
            Object timestamp = wire.get("timestamp");
            if (!(timestamp instanceof String)) {
                Date original = value.getDate("timestamp");
                wire.put("timestamp", (original == null ? Instant.now() : original.toInstant()).toString());
            }
            String json = mapper.writeValueAsString(wire);
            redis.opsForList().leftPush(key(eventId), json);
            redis.opsForList().trim(key(eventId), 0, MAX_MESSAGES - 1);
            return value;
        } catch (Exception error) {
            throw new IllegalStateException("LIVE_EVENT_CHAT_WRITE_FAILED", error);
        }
    }

    public List<Document> getMessages(String eventId) {
        required(eventId, "EVENT_ID_REQUIRED");
        List<String> values = redis.opsForList().range(key(eventId), 0, -1);
        if (values == null || values.isEmpty()) return List.of();
        List<Document> result = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                Document parsed = Document.parse(value);
                Object timestamp = parsed.get("timestamp");
                if (timestamp instanceof String text) {
                    try { parsed.put("timestamp", Date.from(Instant.parse(text))); }
                    catch (RuntimeException ignored) { parsed.put("timestamp", new Date()); }
                }
                result.add(parsed);
            } catch (RuntimeException malformed) {
                // A single legacy/corrupt chat item must not break joining the event.
            }
        }
        return result;
    }

    public void remove(String eventId) {
        if (eventId != null && !eventId.isBlank()) redis.delete(key(eventId));
    }

    private String key(String eventId) { return PREFIX + eventId; }

    private void required(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
    }
}
