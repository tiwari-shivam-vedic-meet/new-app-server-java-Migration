package com.vedicmeet.appserver.consultant;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.LiveKitProvider;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultantRecordingServiceTest {

    private MongoCollection<Document> waitlists;
    private LiveKitProvider liveKit;
    private ConsultantRecordingService service;
    private Document actor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        waitlists = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        liveKit = mock(LiveKitProvider.class);
        service = new ConsultantRecordingService(mongo, liveKit,
                new AppConstants("test-bucket", "ap-south-1"));
        actor = new Document("_id", new ObjectId());
    }

    @Test
    void tokenDefaultsRoomIdentityAndPublishPermission() {
        when(liveKit.participantToken(anyString(), anyString(), eq(true)))
                .thenReturn(Map.of("token", "T"));
        assertEquals("T", service.token(actor, Map.of()).get("token"));
        verify(liveKit).participantToken(startsWith("room_"), startsWith("user_"), eq(true));
    }

    @Test
    void startRecordingPersistsEgressOnlyOnActorOwnedWaitlist() {
        ObjectId waitlistId = new ObjectId();
        find(new Document("_id", waitlistId)
                .append("consultant_id", actor.getObjectId("_id").toHexString()));
        Document egress = new Document("egressId", "EG-1")
                .append("fileResults", List.of(new Document("location",
                        "s3://test-bucket/recordings/file.ogg")));
        when(liveKit.startAudioRecording("room-1")).thenReturn(egress);

        assertSame(egress, service.start(actor,
                Map.of("roomName", "room-1", "waitlistId", waitlistId.toHexString())));

        verify(waitlists).updateOne(any(Bson.class), ArgumentMatchers.<Bson>argThat(update ->
                update instanceof Document d && d.toJson().contains("EG-1")
                        && d.toJson().contains("recordingUrl")));
    }

    @Test
    void anotherConsultantsWaitlistCannotStartARecording() {
        find(null);
        assertEquals("Waitlist not found", assertThrows(IllegalArgumentException.class, () ->
                service.start(actor, Map.of("roomName", "room", "waitlistId",
                        new ObjectId().toHexString()))).getMessage());
        verifyNoInteractions(liveKit);
    }

    @Test
    void stopWithoutAnActiveEgressIsIdempotent() {
        when(liveKit.activeRecordings("room-1")).thenReturn(List.of());
        ConsultantRecordingService.StopResult result = service.stop(actor, Map.of("roomName", "room-1"));
        assertEquals("No active recording found to stop", result.message());
        assertNull(result.data());
        verify(liveKit, never()).stopRecording(anyString());
    }

    @SuppressWarnings("unchecked")
    private void find(Document value) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(value);
        when(waitlists.find(any(Bson.class))).thenReturn(iterable);
    }
}
