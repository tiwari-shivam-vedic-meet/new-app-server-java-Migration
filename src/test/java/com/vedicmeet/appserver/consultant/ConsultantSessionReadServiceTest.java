package com.vedicmeet.appserver.consultant;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultantSessionReadServiceTest {

    private MongoTemplate mongo;
    private MongoCollection<Document> seeds;
    private MongoCollection<Document> events;
    private MongoCollection<Document> waitlists;
    private ChatServerClient chat;
    private ConsultantSessionReadService service;
    private Document actor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        mongo = mock(MongoTemplate.class);
        seeds = mock(MongoCollection.class);
        events = mock(MongoCollection.class);
        waitlists = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.SEED_MASTERS)).thenReturn(seeds);
        when(mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS)).thenReturn(events);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        chat = mock(ChatServerClient.class);
        service = new ConsultantSessionReadService(mongo, chat);
        actor = new Document("_id", new ObjectId()).append("accountName", "Astrologer")
                .append("profileImage", "image");
    }

    @Test
    void initializeReturnsNodeThreadAndSeedShape() {
        find(seeds, new Document("data", new Document("max", 10)));
        when(chat.createThreadAdmin(anyMap())).thenReturn(Map.of("success", true, "data", "thread-1"));

        Map<String, Object> result = service.initialize(actor);

        assertEquals("thread-1", result.get("threadId"));
        assertEquals(10, ((Document) result.get("consultantMasterSettings")).get("max"));
        verify(chat).createThreadAdmin(argThat(payload -> payload.containsKey("user")
                && payload.containsKey("admin")));
    }

    @Test
    void explicitLiveEventReadKeepsProductionCompatibility() {
        ObjectId eventId = new ObjectId();
        Document event = new Document("_id", eventId).append("status", "live");
        find(events, event);
        assertSame(event, service.liveEvent(actor, eventId.toHexString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeWaitlistAggregationIsScopedToAuthenticatedConsultant() {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(waitlists.aggregate(anyList())).thenReturn(aggregate);
        when(aggregate.into(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.activeWaitlist(actor);

        ArgumentCaptor<List<? extends Bson>> pipeline = ArgumentCaptor.forClass(List.class);
        verify(waitlists).aggregate(pipeline.capture());
        String json = ((Document) pipeline.getValue().get(0)).toJson();
        assertTrue(json.contains(actor.getObjectId("_id").toHexString()), json);
        assertTrue(json.contains("waiting") && json.contains("progress") && json.contains("missed"), json);
    }

    @SuppressWarnings("unchecked")
    private void find(MongoCollection<Document> collection, Document value) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(value);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
    }
}
