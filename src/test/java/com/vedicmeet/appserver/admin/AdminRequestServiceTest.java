package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRequestServiceTest {

    private AdminRequestService service(MongoTemplate mongo) {
        return new AdminRequestService(mongo, new AdminMongoSupport(mongo), new AppConstants("bucket", "ap-south-1"));
    }

    @Test
    void listUsesReqListKey() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> requests = collection(mongo, Collections.REQUESTS);
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(requests.aggregate(anyList())).thenReturn(aggregate);
        Document facet = new Document("list", new ArrayList<Document>()).append("count", new ArrayList<Document>());
        when(aggregate.into(any())).thenReturn(new ArrayList<>(List.of(facet)));

        Map<String, Object> result = service(mongo).list(new HashMap<>());

        // FAITHFUL(node-quirk): request list payload key is "reqList", not "list".
        assertTrue(result.containsKey("reqList"));
        assertEquals(0L, result.get("total"));
    }

    @Test
    void detailsRejectsMissingRequest() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        firstReturns(mongo, Collections.REQUESTS, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).details(Map.of("requestId", "507f1f77bcf86cd799439011")));

        assertEquals("REQUEST_NOT_EXIST", error.getMessage());
    }

    @Test
    void acceptAndRejectRejectsAlreadyApproved() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        firstReturns(mongo, Collections.REQUESTS,
                new Document("_id", new ObjectId()).append("approveStatus", 2).append("requestType", "BANK"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).acceptAndReject(request(3)));

        assertEquals("REQUEST_ALREADY_APPROVED", error.getMessage());
    }

    @Test
    void acceptAndRejectRejectsAlreadyRejected() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        firstReturns(mongo, Collections.REQUESTS,
                new Document("_id", new ObjectId()).append("approveStatus", 3).append("requestType", "BANK"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).acceptAndReject(request(2)));

        assertEquals("REQUEST_ALREADY_REJECTED", error.getMessage());
    }

    private Map<String, Object> request(int approveStatus) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "507f1f77bcf86cd799439011");
        body.put("approveStatus", approveStatus);
        return body;
    }

    @SuppressWarnings("unchecked")
    private void firstReturns(MongoTemplate mongo, String name, Document value) {
        MongoCollection<Document> coll = collection(mongo, name);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(value);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }
}
