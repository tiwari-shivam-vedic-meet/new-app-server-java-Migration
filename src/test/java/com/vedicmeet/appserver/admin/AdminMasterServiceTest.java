package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMasterServiceTest {

    private final AppConstants constants = new AppConstants("bucket", "ap-south-1");

    private AdminMasterService service(MongoTemplate mongo, CacheService cache) {
        return new AdminMasterService(mongo, new AdminMongoSupport(mongo), constants, cache);
    }

    @Test
    void listReturnsEveryMasterSectionPlusRewardUrl() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> find = emptyFind();
        AggregateIterable<Document> aggregate = emptyAggregate();
        when(mongo.getCollection(anyString())).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(coll.aggregate(anyList())).thenReturn(aggregate);

        Map<String, Object> result = service(mongo, mock(CacheService.class)).list(new HashMap<>(), "admin1");

        for (String key : List.of("categoryList", "musicCategorylist", "meditationCategoryList", "vedicPranayam",
                "skillList", "languageList", "masterData", "giftList", "rechargeList", "membershipPlans",
                "communityList", "rewardUrl")) {
            assertTrue(result.containsKey(key), "missing key: " + key);
        }
        assertEquals(constants.tagRewardsBanner, result.get("rewardUrl"));
    }

    @Test
    void addEditEditStripsNonSchemaKeysFromSetAndInvalidatesCache() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> masters = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.MASTERS)).thenReturn(masters);
        when(masters.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", new ObjectId()).append("supportNumber", "123"));
        CacheService cache = mock(CacheService.class);

        Map<String, Object> body = new HashMap<>();
        body.put("type", "edit");
        body.put("masterId", "507f1f77bcf86cd799439011");
        body.put("supportNumber", "123");
        body.put("problems", List.of(new Document("problem", "p").append("message", "m")));
        body.put("hackerField", "nope");

        Map<String, Object> result = service(mongo, cache).addEdit(body, "admin1");

        assertNotNull(result);
        ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(masters).findOneAndUpdate(any(Bson.class), updateCaptor.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) updateCaptor.getValue()).get("$set");
        assertTrue(set.containsKey("supportNumber"));
        assertTrue(set.containsKey("problems"));
        assertFalse(set.containsKey("type"));
        assertFalse(set.containsKey("masterId"));
        assertFalse(set.containsKey("hackerField"));
        verify(cache).invalidate("master:data:*");
    }

    @Test
    void addEditAddWhitelistsSchemaFieldsAndAppliesArrayDefaults() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> masters = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.MASTERS)).thenReturn(masters);

        Map<String, Object> body = new HashMap<>();
        body.put("type", "add");
        body.put("supportNumber", "9");
        body.put("hackerField", "nope");

        service(mongo, mock(CacheService.class)).addEdit(body, "admin1");

        ArgumentCaptor<Document> insertCaptor = ArgumentCaptor.forClass(Document.class);
        verify(masters).insertOne(insertCaptor.capture());
        Document inserted = insertCaptor.getValue();
        assertEquals("9", inserted.get("supportNumber"));
        assertNotNull(inserted.get("_id"));
        assertEquals(new ArrayList<>(), inserted.get("taskRepeats"));
        assertEquals(new ArrayList<>(), inserted.get("problems"));
        assertFalse(inserted.containsKey("type"));
        assertFalse(inserted.containsKey("hackerField"));
    }

    @Test
    void addEditWithUnknownTypeReturnsNull() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Map<String, Object> body = new HashMap<>();
        body.put("type", "delete");
        assertNull(service(mongo, mock(CacheService.class)).addEdit(body, "admin1"));
    }

    @Test
    void deadEndpointsReproduceNodeTypeErrors() {
        AdminMasterService service = service(mock(MongoTemplate.class), mock(CacheService.class));
        assertEquals("MasterService.addVimshotri is not a function",
                assertThrows(IllegalStateException.class, () -> service.addVimshotri(new HashMap<>(), "a")).getMessage());
        assertEquals("MasterService.queueClear is not a function",
                assertThrows(IllegalStateException.class, () -> service.clear(new HashMap<>(), "a")).getMessage());
        assertEquals("MasterService.getCategory is not a function",
                assertThrows(IllegalStateException.class, () -> service.getCategory(new HashMap<>(), "a")).getMessage());
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> emptyFind() {
        FindIterable<Document> find = mock(FindIterable.class);
        when(find.projection(any(Bson.class))).thenReturn(find);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.into(any())).thenAnswer(inv -> inv.getArgument(0));
        when(find.first()).thenReturn(null);
        return find;
    }

    @SuppressWarnings("unchecked")
    private AggregateIterable<Document> emptyAggregate() {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(aggregate.into(any())).thenAnswer(inv -> inv.getArgument(0));
        return aggregate;
    }
}
