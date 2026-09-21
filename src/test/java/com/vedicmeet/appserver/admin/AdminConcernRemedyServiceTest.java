package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConcernRemedyServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";

    private AdminConcernRemedyService service(MongoTemplate mongo) {
        return new AdminConcernRemedyService(mongo, new AdminMongoSupport(mongo));
    }

    @Test
    void listAreaOfConcernReturnsListWithIdAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.AREA_OF_CONCERNS);
        ObjectId id = new ObjectId();
        FindIterable<Document> find = findChain(coll, List.of(new Document("_id", id).append("title", "Health")));
        when(coll.countDocuments(any(Bson.class))).thenReturn(1L);

        Map<String, Object> result = service(mongo).listAreaOfConcern(new HashMap<>());

        List<?> list = (List<?>) result.get("list");
        assertEquals(1, list.size());
        assertEquals(id.toHexString(), ((Document) list.get(0)).get("id"));
        assertEquals(1L, result.get("total"));
        verify(find).sort(new Document("createdAt", -1));
    }

    @Test
    void addAreaOfConcernThrowsWhenTitleExists() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.AREA_OF_CONCERNS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(new Document("title", "Health"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).addAreaOfConcern(Map.of("title", "Health")));

        assertEquals("TITLE_EXIST", error.getMessage());
    }

    @Test
    void addAreaOfConcernPersistsWhitelistWithDefaults() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.AREA_OF_CONCERNS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo)
                .addAreaOfConcern(Map.of("title", "Health", "ignored", "drop-me"));

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertEquals("Health", saved.get("title"));
        assertEquals(true, saved.get("status"));
        assertTrue(saved.containsKey("createdAt"));
        assertTrue(saved.containsKey("updatedAt"));
        assertFalse(saved.containsKey("ignored"));
        assertEquals(saved.get("_id").toString(), out.get("id"));
    }

    @Test
    void updateAreaOfConcernThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.AREA_OF_CONCERNS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).updateAreaOfConcern(Map.of("areaOfConcernId", OID, "title", "X")));

        assertEquals("AREA_OF_CONCERN_NOT_EXIST", error.getMessage());
    }

    @Test
    void updateAreaOfConcernSetsStrictWhitelistAndUpdatedAt() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.AREA_OF_CONCERNS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        // exist -> present, titleExist -> null
        when(find.first()).thenReturn(new Document("_id", new ObjectId()), (Document) null);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", new ObjectId()).append("title", "New"));
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        service(mongo).updateAreaOfConcern(new HashMap<>(Map.of(
                "areaOfConcernId", OID, "title", "New", "status", "false")));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = ((Document) update.getValue()).get("$set", Document.class);
        assertEquals("New", set.get("title"));
        assertEquals(false, set.get("status"));
        assertTrue(set.containsKey("updatedAt"));
        assertNull(set.get("areaOfConcernId"));
    }

    @Test
    void editRemedyMediaThrowsExistWhenNotFound() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.AREA_OF_CONCERN_REMEDIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).editAreaOfConcernRemedyMedia(Map.of("areaOfConcernRemedyId", OID)));

        // FAITHFUL(node-quirk): "not exist" path throws AREA_OF_CONCERN_REMEDY_EXIST.
        assertEquals("AREA_OF_CONCERN_REMEDY_EXIST", error.getMessage());
    }

    @Test
    void blockUnblockRemedyMediaIsDeadEndpoint() {
        MongoTemplate mongo = mock(MongoTemplate.class);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).blockUnblockAreaOfConcernRemedyMedia(Map.of()));

        assertEquals("ConcernRemedyService.blockUnblockAreaOfConcernRemedyMedia is not a function",
                error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> findChain(MongoCollection<Document> coll, List<Document> rows) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.skip(anyInt())).thenReturn(find);
        when(find.limit(anyInt())).thenReturn(find);
        when(find.into(any())).thenReturn(new ArrayList<>(rows));
        return find;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }
}
