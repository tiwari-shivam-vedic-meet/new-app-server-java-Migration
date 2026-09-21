package com.vedicmeet.appserver.consultant;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.session.SessionBookingStore;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultantSessionAssignmentServiceTest {

    private MongoCollection<Document> waitlists;
    private MongoCollection<Document> fixed;
    private MongoCollection<Document> consultants;
    private ChatServerClient chat;
    private ConsultantSessionAssignmentService service;
    private Document actor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        waitlists = mock(MongoCollection.class);
        fixed = mock(MongoCollection.class);
        consultants = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        when(mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS)).thenReturn(fixed);
        when(mongo.getCollection(Collections.CONSULTANTS)).thenReturn(consultants);
        chat = mock(ChatServerClient.class);
        service = new ConsultantSessionAssignmentService(mongo, chat,
                mock(SessionBookingStore.class), mock(CallIntegrationOutboxService.class),
                mock(MongoTransactionManager.class));
        actor = new Document("_id", new ObjectId()).append("accountName", "Astrologer A");
    }

    @Test
    void fixedSessionAtomicClaimLoserGetsProductionBusinessCode() {
        when(fixed.findOneAndUpdate(any(Bson.class), any(Bson.class),
                any(FindOneAndUpdateOptions.class))).thenReturn(null);

        ConsultantSessionAssignmentService.AssignmentResult result =
                service.acceptFixedSession(actor, new ObjectId().toHexString());

        assertFalse(result.success());
        assertEquals("SESSION_ALREADY_ACCEPTED_BY_ANOTHER_CONSULTANT", result.code());
        verifyNoInteractions(chat);
    }

    @Test
    void alreadyOwnedQueryIsIdempotentForSameConsultant() {
        Document query = new Document("_id", new ObjectId()).append("used_for", "query")
                .append("status", "completed").append("consultant_id", actor.getObjectId("_id").toHexString())
                .append("threadId", "thread");
        find(waitlists, query);

        ConsultantSessionAssignmentService.AssignmentResult result =
                service.acceptQuery(actor, query.getObjectId("_id").toHexString());

        assertTrue(result.success());
        assertEquals("ALREADY_ACCEPTED_BY_YOU", result.code());
        verifyNoInteractions(chat);
    }

    @Test
    void alreadyOwnedQueryReturnsTheOtherConsultantName() {
        ObjectId otherId = new ObjectId();
        Document query = new Document("_id", new ObjectId()).append("used_for", "query")
                .append("status", "completed").append("consultant_id", otherId.toHexString());
        find(waitlists, query);
        find(consultants, new Document("_id", otherId).append("accountName", "Astrologer B"));

        ConsultantSessionAssignmentService.AssignmentResult result =
                service.acceptQuery(actor, query.getObjectId("_id").toHexString());

        assertFalse(result.success());
        assertEquals("ALREADY_ACCEPTED_BY_OTHER", result.code());
        assertTrue(result.message().contains("Astrologer B"));
        verifyNoInteractions(chat);
    }

    @SuppressWarnings("unchecked")
    private void find(MongoCollection<Document> collection, Document value) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(value);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
    }
}
