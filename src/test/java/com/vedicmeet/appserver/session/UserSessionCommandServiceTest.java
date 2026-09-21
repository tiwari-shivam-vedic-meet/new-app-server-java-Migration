package com.vedicmeet.appserver.session;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.LiveKitProvider;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSessionCommandServiceTest {

    private MongoTemplate mongo;
    private CallIntegrationOutboxService outbox;
    private LiveKitProvider liveKit;
    private UserSessionCommandService service;
    private Document actor;

    @BeforeEach
    void setup() {
        mongo = mock(MongoTemplate.class);
        outbox = mock(CallIntegrationOutboxService.class);
        liveKit = mock(LiveKitProvider.class);
        service = new UserSessionCommandService(mongo, outbox, liveKit, mock(MediaUploadService.class));
        actor = new Document("_id", new ObjectId()).append("name", "Test User");
    }

    @Test
    void blockHistoryUsesActorOwnedAtomicUpdate() {
        MongoCollection<Document> waitlists = collection(Collections.WAITLISTS, null);
        when(waitlists.findOneAndUpdate(any(Bson.class), any(Bson.class),
                any(FindOneAndUpdateOptions.class))).thenReturn(new Document("status", "completed"));
        String waitlistId = new ObjectId().toHexString();

        service.setConsultantHistoryAccess(actor,
                Map.of("waitlistId", waitlistId, "canConsultantAccessHistory", false));

        verify(waitlists).findOneAndUpdate(argThat(filter -> filter instanceof Document d && d.toJson()
                        .contains(actor.getObjectId("_id").toHexString())), any(Bson.class),
                any(FindOneAndUpdateOptions.class));
    }

    @Test
    void existingDailyQueryReturnsTheNodeBusinessError() {
        collection(Collections.WAITLISTS, new Document("_id", new ObjectId()));
        assertThrows(UserSessionCommandService.DailyQueryLimitException.class,
                () -> service.createQuickQuery(actor, Map.of("queryText", "Career?")));
        verifyNoInteractions(outbox);
    }

    @Test
    void emptyQuickQueryIsRejectedBeforeDatabaseAccess() {
        assertEquals("Query text is required", assertThrows(IllegalArgumentException.class,
                () -> service.createQuickQuery(actor, Map.of("queryText", "  "))).getMessage());
        verifyNoInteractions(mongo, outbox);
    }

    @Test
    void liveKitTokenDefaultsIdentityAndScopesProvidedWaitlistToUser() {
        MongoCollection<Document> waitlists = collection(Collections.WAITLISTS,
                new Document("_id", new ObjectId()));
        when(liveKit.participantToken(anyString(), anyString(), eq(true)))
                .thenReturn(Map.of("token", "TOKEN"));
        String waitlistId = new ObjectId().toHexString();

        assertEquals("TOKEN", service.generateLiveKitToken(actor,
                Map.of("waitlistId", waitlistId)).get("token"));
        verify(waitlists).find(org.mockito.ArgumentMatchers.<Bson>argThat(filter ->
                filter instanceof Document d && d.toJson()
                        .contains(actor.getObjectId("_id").toHexString())));
        verify(liveKit).participantToken(startsWith("room_"), startsWith("user_"), eq(true));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(String name, Document first) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> result = mock(FindIterable.class);
        when(result.first()).thenReturn(first);
        when(result.projection(any(Bson.class))).thenReturn(result);
        when(collection.find(any(Bson.class))).thenReturn(result);
        when(mongo.getCollection(name)).thenReturn(collection);
        return collection;
    }
}
