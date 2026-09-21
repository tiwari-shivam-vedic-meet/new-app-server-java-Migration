package com.vedicmeet.appserver.realtime;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIONamespace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small Socket.IO transport seam. The current-client binding lets dispatcher error events preserve
 * Node's {@code socket.emit(...)} behavior; room emissions use the attached /app namespace.
 */
@Component
public class SocketEventPublisher {

    static final String CHANNEL = "socket:io:emit";
    private static final Logger log = LoggerFactory.getLogger(SocketEventPublisher.class);

    private final ThreadLocal<SocketIOClient> currentClient = new ThreadLocal<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean fanoutEnabled;
    private final String instanceId;
    private final Map<String, SocketIONamespace> namespaces = new ConcurrentHashMap<>();

    @Autowired
    public SocketEventPublisher(StringRedisTemplate redis, ObjectMapper mapper,
                                @Value("${vedicmeet.socket.redis-fanout-enabled:false}") boolean fanoutEnabled) {
        this(redis, mapper, fanoutEnabled, UUID.randomUUID().toString());
    }

    SocketEventPublisher(StringRedisTemplate redis, ObjectMapper mapper,
                         boolean fanoutEnabled, String instanceId) {
        this.redis = redis;
        this.mapper = mapper;
        this.fanoutEnabled = fanoutEnabled;
        this.instanceId = instanceId;
    }

    void attach(SocketIONamespace namespace) {
        attach("app", namespace);
    }

    void attach(String namespace, SocketIONamespace target) {
        if (target != null) namespaces.put(normalize(namespace), target);
    }

    void detach() {
        namespaces.clear();
    }

    void detach(String namespace) {
        namespaces.remove(normalize(namespace));
    }

    void bind(SocketIOClient client) {
        currentClient.set(client);
    }

    void unbind() {
        currentClient.remove();
    }

    public void emitToCurrent(String event, Object payload) {
        SocketIOClient client = currentClient.get();
        if (client != null && client.isChannelOpen()) client.sendEvent(event, payload);
    }

    public void emitToRoom(String room, String event, Object payload) {
        emitToRoom("app", room, event, payload);
    }

    /** Namespace-aware room delivery; the three-argument form remains the /app default. */
    public void emitToRoom(String namespace, String room, String event, Object payload) {
        String targetNamespace = normalize(namespace);
        emitLocalToRoom(targetNamespace, room, event, payload);
        if (!fanoutEnabled || room == null || room.isBlank()) return;
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            // Node socket-emitter.js expects namespace without the leading slash.
            envelope.put("namespace", targetNamespace);
            envelope.put("room", room);
            envelope.put("event", event);
            envelope.put("data", payload);
            envelope.put("origin", "java:" + instanceId);
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(envelope));
        } catch (Exception error) {
            log.warn("socket fan-out publish failed room={} event={}", room, event);
        }
    }

    /** Called by the Redis subscriber for Node/background-worker and peer-Java emissions. */
    void acceptFanout(String json) {
        try {
            JsonNode envelope = mapper.readTree(json);
            if (("java:" + instanceId).equals(envelope.path("origin").asText())) return;
            String namespace = normalize(envelope.path("namespace").asText("app"));
            String room = envelope.path("room").asText("");
            String event = envelope.path("event").asText("");
            if (room.isBlank() || event.isBlank()) return;
            Object data = envelope.has("data")
                    ? mapper.convertValue(envelope.get("data"), Object.class) : null;
            emitLocalToRoom(namespace, room, event, data);
        } catch (Exception error) {
            log.warn("socket fan-out message rejected");
        }
    }

    private void emitLocalToRoom(String namespace, String room, String event, Object payload) {
        SocketIONamespace target = namespaces.get(normalize(namespace));
        if (target != null && room != null && !room.isBlank()) {
            target.getRoomOperations(room).sendEvent(event, payload);
        }
    }

    private String normalize(String namespace) {
        if (namespace == null || namespace.isBlank()) return "app";
        return namespace.startsWith("/") ? namespace.substring(1) : namespace;
    }
}
