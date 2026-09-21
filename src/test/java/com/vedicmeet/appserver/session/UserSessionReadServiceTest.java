package com.vedicmeet.appserver.session;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.vedicmeet.appserver.support.ChatServerClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserSessionReadServiceTest {

    @Test
    void waitlistCount_matchesCompletedOnlyNodeFilter() {
        Fixture f = fixture();
        when(f.waitlists.countDocuments(any(Bson.class))).thenReturn(7L);

        assertEquals(7, f.service.waitlistCount("user-1"));

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(f.waitlists).countDocuments(filter.capture());
        assertEquals(Document.parse("{user_id:'user-1',status:{$in:['completed']}}"), filter.getValue());
    }

    @Test
    void progressCall_usesWaitlistIdButChatThreadAsRoomId() {
        ObjectId waitlistId = new ObjectId();
        Fixture f = fixture();
        f.findResult(f.waitlists, new Document("_id", waitlistId)
                .append("user_id", "user-1").append("status", "progress")
                .append("threadId", "chat-thread-9")
                .append("session_info", new Document("mode", "audio")));

        Map<String, Object> result = f.service.progressCall("user-1");

        assertEquals(waitlistId.toHexString(), result.get("waitlistId"));
        assertEquals("chat-thread-9", result.get("roomId"));
        assertEquals("audio", result.get("channel"));
        assertEquals(false, ((Map<?, ?>) result.get("permissions")).get("canShareVideo"));
    }

    @Test
    void progressCall_withoutProgressRow_returnsNull() {
        Fixture f = fixture();
        f.findResult(f.waitlists, null);
        assertNull(f.service.progressCall("user-1"));
    }

    @Test
    void requestForms_returnsNodeAggregationOutputWithoutRemapping() {
        Fixture f = fixture();
        Document grouped = new Document("request_form", new Document("firstName", "A")).append("count", 2);
        f.aggregateResult(f.waitlists, List.of(grouped));
        assertEquals(List.of(grouped), f.service.requestForms("user-1"));
        verify(f.waitlists).aggregate(anyList());
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> waitlists = mock(MongoCollection.class);
        MongoCollection<Document> wallet = mock(MongoCollection.class);
        MongoCollection<Document> seed = mock(MongoCollection.class);
        MongoCollection<Document> reviews = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        when(mongo.getCollection(Collections.WALLET_TRANSACTIONS)).thenReturn(wallet);
        when(mongo.getCollection(Collections.SEED_MASTERS)).thenReturn(seed);
        when(mongo.getCollection(Collections.PLAYSTORE_REVIEW_UPLOADS)).thenReturn(reviews);
        AppConstants constants = new AppConstants("bucket", "ap-south-1");
        return new Fixture(new UserSessionReadService(mongo, constants, mock(ChatServerClient.class)), waitlists);
    }

    private record Fixture(UserSessionReadService service, MongoCollection<Document> waitlists) {
        @SuppressWarnings("unchecked")
        void findResult(MongoCollection<Document> collection, Document result) {
            FindIterable<Document> iterable = mock(FindIterable.class);
            when(iterable.first()).thenReturn(result);
            when(collection.find(any(Bson.class))).thenReturn(iterable);
        }

        @SuppressWarnings("unchecked")
        void aggregateResult(MongoCollection<Document> collection, List<Document> result) {
            AggregateIterable<Document> iterable = mock(AggregateIterable.class);
            when(iterable.into(any(List.class))).thenAnswer(invocation -> {
                List<Document> target = invocation.getArgument(0);
                target.addAll(result);
                return target;
            });
            when(collection.aggregate(anyList())).thenReturn(iterable);
        }
    }
}
