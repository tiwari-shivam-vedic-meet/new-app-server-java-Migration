package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminEventServiceTest {

    private AdminEventService service(MongoTemplate mongo) {
        return new AdminEventService(mongo, new AdminMongoSupport(mongo), new AppConstants("bucket", "ap-south-1"));
    }

    @Test
    void listReturnsListAndTotalFromFacet() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.BROADCASTS);
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        Document facet = new Document("list", List.of(new Document("eventName", "x")))
                .append("count", List.of(new Document("total", 3)));
        when(aggregate.into(any())).thenReturn(new ArrayList<>(List.of(facet)));

        Map<String, Object> result = service(mongo).list(new HashMap<>());

        assertEquals(1, ((List<?>) result.get("list")).size());
        assertEquals(3L, result.get("total"));
    }

    @Test
    void sendMessagesRejectsUnknownBroadcastBeforeInsert() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> broadcasts = collection(mongo, Collections.BROADCASTS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(broadcasts.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).sendMessages(Map.of("broadcastId", "507f1f77bcf86cd799439011")));

        assertEquals("BROADCAST_NOT_EXIST", error.getMessage());
        verify(mongo, never()).getCollection(Collections.BROADCAST_MESSAGES);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }
}
