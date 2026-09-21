package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConsServiceTest {

    @Test
    void listReturnsPaginationShapeWithNodeDefaults() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        FindIterable<Document> find = findChain(coll);
        when(find.into(any())).thenReturn(new ArrayList<Document>());
        when(coll.countDocuments(any(Bson.class))).thenReturn(0L);

        Map<String, Object> data = new AdminConsService(mongo, new AdminMongoSupport(mongo)).list(new HashMap<>());

        assertEquals(new ArrayList<>(), data.get("list"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) data.get("pagination");
        // FAITHFUL: cons.js default page is 0 (0-indexed) and totals are 0 on an empty collection.
        assertEquals(0, pagination.get("pageNumber"));
        assertEquals(0L, pagination.get("pageCount"));
        assertEquals(0L, pagination.get("totalDocuments"));
    }

    @Test
    void listSkipsPageTimesPageSize() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        FindIterable<Document> find = findChain(coll);
        when(find.into(any())).thenReturn(new ArrayList<Document>());
        when(coll.countDocuments(any(Bson.class))).thenReturn(0L);

        Map<String, String> query = new HashMap<>();
        query.put("page", "2");
        query.put("pageSize", "10");
        new AdminConsService(mongo, new AdminMongoSupport(mongo)).list(query);

        // FAITHFUL(node-quirk): skip = page * pageSize with a 0-indexed page (2 * 10 = 20), not (page - 1) * pageSize.
        verify(find).skip(20);
        verify(find).limit(10);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> findChain(MongoCollection<Document> coll) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.skip(anyInt())).thenReturn(find);
        when(find.limit(anyInt())).thenReturn(find);
        return find;
    }
}
