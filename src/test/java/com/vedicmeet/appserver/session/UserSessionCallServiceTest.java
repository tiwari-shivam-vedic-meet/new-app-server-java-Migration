package com.vedicmeet.appserver.session;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.call.CallAcceptService;
import com.vedicmeet.appserver.call.CallCancelService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSessionCallServiceTest {

    @Test
    void cancel_deletesPendingPushRecordThenUsesSharedCancelStateMachine() {
        Fixture f = fixture();
        String room = new ObjectId().toHexString();
        when(f.initiated.findOneAndDelete(any(Bson.class))).thenReturn(
                new Document("userPayload", new Document("sessionId", room)));

        assertNull(f.service.callStatus("user-1", "cancel", null));
        verify(f.cancel).cancelCall(room, "user-1", null);
    }

    @Test
    void pick_atomicallyMarksPushAcceptedAndAcceptsTheCall() {
        Fixture f = fixture();
        String room = new ObjectId().toHexString();
        Document row = new Document("userPayload", new Document("sessionId", room));
        when(f.initiated.findOneAndUpdate(any(Bson.class), any(Bson.class),
                any(FindOneAndUpdateOptions.class))).thenReturn(row);

        assertSame(row, f.service.callStatus("user-1", "pick", null));
        verify(f.accept).acceptCall(room, "user-1", null, null);
    }

    @Test
    void voipAccepted_inBackground_doesNotDoubleAccept() {
        Fixture f = fixture();
        when(f.initiated.findOneAndUpdate(any(Bson.class), any(Bson.class),
                any(FindOneAndUpdateOptions.class))).thenReturn(
                new Document("userPayload", new Document("sessionId", new ObjectId().toHexString())));
        f.service.callStatus("user-1", "voip_accepted", "background");
        verifyNoInteractions(f.accept);
    }

    @Test
    void acceptIncoming_secondPartyReturnsNodeJoinPayloadIdentifierShape() {
        Fixture f = fixture();
        ObjectId room = new ObjectId();
        when(f.accept.acceptCall(room.toHexString(), "user-1", null, null))
                .thenReturn(CallAcceptService.AcceptOutcome.PROGRESS_STARTED);
        Document waitlist = new Document("_id", room).append("user_id", "user-1")
                .append("threadId", "chat-thread")
                .append("used_for", "private_call")
                .append("session_info", new Document("mode", "audio"));
        findResult(f.waitlists, waitlist);

        Map<String, Object> result = f.service.acceptIncoming("user-1", room.toHexString(), null);
        assertEquals(room.toHexString(), result.get("waitlistId"));
        assertEquals("chat-thread", result.get("roomId"));
        assertEquals("audio", result.get("channel"));
        assertEquals(java.util.List.of("chat", "audio"), result.get("accessbilities"));
    }

    @Test
    void acceptIncoming_firstPartyReturnsNullLikeNode() {
        Fixture f = fixture();
        when(f.accept.acceptCall("room", "user-1", null, null))
                .thenReturn(CallAcceptService.AcceptOutcome.PARTIALLY_ACCEPTED);
        assertNull(f.service.acceptIncoming("user-1", "room", null));
        verifyNoInteractions(f.waitlists);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> initiated = mock(MongoCollection.class);
        MongoCollection<Document> waitlists = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.CALL_INITIATED)).thenReturn(initiated);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        CallAcceptService accept = mock(CallAcceptService.class);
        CallCancelService cancel = mock(CallCancelService.class);
        return new Fixture(new UserSessionCallService(mongo, accept, cancel), initiated,
                waitlists, accept, cancel);
    }

    @SuppressWarnings("unchecked")
    private static void findResult(MongoCollection<Document> collection, Document result) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.first()).thenReturn(result);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
    }

    private record Fixture(UserSessionCallService service, MongoCollection<Document> initiated,
                           MongoCollection<Document> waitlists, CallAcceptService accept,
                           CallCancelService cancel) {}
}
