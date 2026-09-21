package com.vedicmeet.appserver.consultant;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallAcceptService;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultantSessionCallServiceTest {

    private MongoTemplate mongo;
    private MongoCollection<Document> initiated;
    private MongoCollection<Document> waitlists;
    private MongoCollection<Document> relations;
    private MongoCollection<Document> consultants;
    private CallAcceptService accept;
    private CallIntegrationOutboxService outbox;
    private ConsultantSessionCallService service;
    private Document actor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongo = mock(MongoTemplate.class);
        initiated = mock(MongoCollection.class);
        waitlists = mock(MongoCollection.class);
        relations = mock(MongoCollection.class);
        consultants = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.CALL_INITIATED)).thenReturn(initiated);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        when(mongo.getCollection(Collections.USER_CONS_RELS)).thenReturn(relations);
        when(mongo.getCollection(Collections.CONSULTANTS)).thenReturn(consultants);
        accept = mock(CallAcceptService.class);
        outbox = mock(CallIntegrationOutboxService.class);
        service = new ConsultantSessionCallService(mongo, accept, outbox);
        actor = new Document("_id", new ObjectId());
    }

    @Test
    void cancelAndPickPreserveProductionNoOpContract() {
        service.callStatus(actor, "cancel", "opened");
        service.callStatus(actor, "pick", "opened");
        verifyNoInteractions(mongo, accept, outbox);
    }

    @Test
    void voipAcceptedInOpenedAppUsesSharedAtomicAcceptStateMachine() {
        String roomId = new ObjectId().toHexString();
        when(initiated.findOneAndUpdate(any(Bson.class), any(Bson.class),
                any(FindOneAndUpdateOptions.class))).thenReturn(
                new Document("userPayload", new Document("sessionId", roomId)));

        service.callStatus(actor, "voip_accepted", "opened");

        verify(accept).acceptCall(roomId, null, actor.getObjectId("_id").toHexString(), null);
    }

    @Test
    void firstAcceptanceReturnsNullLikeNode() {
        when(accept.acceptCall("room", null, actor.getObjectId("_id").toHexString(), null))
                .thenReturn(CallAcceptService.AcceptOutcome.PARTIALLY_ACCEPTED);
        assertNull(service.acceptIncoming(actor, "room", null));
        verifyNoInteractions(waitlists);
    }

    @Test
    void connectedAcceptanceReturnsThreadIdAsRoomId() {
        ObjectId roomId = new ObjectId();
        when(accept.acceptCall(roomId.toHexString(), null, actor.getObjectId("_id").toHexString(), null))
                .thenReturn(CallAcceptService.AcceptOutcome.PROGRESS_STARTED);
        Document row = new Document("_id", roomId)
                .append("consultant_id", actor.getObjectId("_id").toHexString())
                .append("threadId", "chat-thread").append("used_for", "private_call")
                .append("session_info", new Document("mode", "audio"));
        find(waitlists, row);

        Map<String, Object> result = service.acceptIncoming(actor, roomId.toHexString(), null);

        assertEquals(roomId.toHexString(), result.get("waitlistId"));
        assertEquals("chat-thread", result.get("roomId"));
        assertEquals(java.util.List.of("chat", "audio"), result.get("accessbilities"));
    }

    @Test
    void notifyRequiresActorOwnedWaitlistAndEnqueuesDurableCall() {
        ObjectId roomId = new ObjectId();
        Document row = waitlist(roomId);
        when(waitlists.findOneAndUpdate(any(Bson.class), any(Bson.class),
                any(FindOneAndUpdateOptions.class))).thenReturn(row);

        assertSame(row, service.notifyUserToConnect(actor, roomId.toHexString()));

        verify(outbox).enqueue(startsWith("CALL_INITIATE:notify:"), eq("CALL_INITIATE"),
                argThat(payload -> roomId.toHexString().equals(payload.getString("waitlistId"))
                        && actor.getObjectId("_id").toHexString().equals(payload.getString("consultantId"))));
    }

    @Test
    void blockIsAReviewRequestNotAnImmediateBlock() {
        ObjectId roomId = new ObjectId();
        find(waitlists, waitlist(roomId));

        service.requestBlock(actor, roomId.toHexString(), "abuse");

        verify(relations).updateOne(any(Bson.class), ArgumentMatchers.<Bson>argThat(update -> {
            String json = update instanceof Document document ? document.toJson() : String.valueOf(update);
            return json.contains("isUserBlocked") && json.contains("false")
                    && json.contains("isAdminVerify") && json.contains("abuse");
        }), any(UpdateOptions.class));
    }

    private Document waitlist(ObjectId id) {
        return new Document("_id", id).append("user_id", new ObjectId().toHexString())
                .append("consultant_id", actor.getObjectId("_id").toHexString())
                .append("session_info", new Document("mode", "chat"));
    }

    @SuppressWarnings("unchecked")
    private void find(MongoCollection<Document> collection, Document result) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(result);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
    }
}
