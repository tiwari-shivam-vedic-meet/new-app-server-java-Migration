package com.vedicmeet.appserver.realtime;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIONamespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SocketEventPublisherTest {

    @Test
    void emitToRoom_deliversLocallyAndPublishesNodeCompatibleEnvelope() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SocketIONamespace namespace = mock(SocketIONamespace.class);
        BroadcastOperations room = mock(BroadcastOperations.class);
        when(namespace.getRoomOperations("user-1")).thenReturn(room);
        SocketEventPublisher publisher = new SocketEventPublisher(
                redis, new ObjectMapper(), true, "instance-a");
        publisher.attach(namespace);

        publisher.emitToRoom("user-1", "join_call_room", Map.of("roomId", "thread-1"));

        verify(room).sendEvent(eq("join_call_room"), any());
        verify(redis).convertAndSend(eq("socket:io:emit"), contains("\"namespace\":\"app\""));
    }

    @Test
    void acceptFanout_skipsOwnMessageButDeliversNodeOrPeerMessage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SocketIONamespace namespace = mock(SocketIONamespace.class);
        BroadcastOperations room = mock(BroadcastOperations.class);
        when(namespace.getRoomOperations("user-1")).thenReturn(room);
        SocketEventPublisher publisher = new SocketEventPublisher(
                redis, new ObjectMapper(), true, "instance-a");
        publisher.attach(namespace);

        publisher.acceptFanout("{\"namespace\":\"app\",\"room\":\"user-1\","
                + "\"event\":\"call_missed\",\"data\":{},\"origin\":\"java:instance-a\"}");
        verifyNoInteractions(room);

        publisher.acceptFanout("{\"namespace\":\"app\",\"room\":\"user-1\","
                + "\"event\":\"call_missed\",\"data\":{}}");
        verify(room).sendEvent(eq("call_missed"), any());
    }

    @Test
    void liveEventEnvelopeUsesAndTargetsLiveEventNamespace() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SocketIONamespace app = mock(SocketIONamespace.class);
        SocketIONamespace live = mock(SocketIONamespace.class);
        BroadcastOperations liveRoom = mock(BroadcastOperations.class);
        when(live.getRoomOperations("event-1")).thenReturn(liveRoom);
        SocketEventPublisher publisher = new SocketEventPublisher(
                redis, new ObjectMapper(), true, "instance-a");
        publisher.attach(app);
        publisher.attach("live_event", live);

        publisher.emitToRoom("live_event", "event-1", "receive_message", Map.of("message", "hi"));

        verify(liveRoom).sendEvent(eq("receive_message"), any());
        verify(redis).convertAndSend(eq("socket:io:emit"), contains("\"namespace\":\"live_event\""));
        verifyNoInteractions(app);
    }
}
