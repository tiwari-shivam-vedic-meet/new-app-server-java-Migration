package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminVastuCompassServiceTest {

    @Test
    void addCategoryRequiresCategoryBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addVastuCompassCategory(Map.of("title", "Bedroom")));

        assertEquals("\"category\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void addCategoryRequiresTitleBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addVastuCompassCategory(Map.of("category", "Home")));

        assertEquals("\"title\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void updateCategoryRequiresVastuCategoryIdBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateVastuCategory(Map.of("title", "Bedroom")));

        assertEquals("\"vastuCategoryId\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void blockUnblockRequiresVastuCategoryIdBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.blockUnblockCategory(Map.of("status", true)));

        assertEquals("\"vastuCategoryId\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void blockUnblockRequiresStatusBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.blockUnblockCategory(Map.of("vastuCategoryId", "507f1f77bcf86cd799439011")));

        assertEquals("\"status\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void blockUnblockRequiresBooleanStatusBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.blockUnblockCategory(Map.of("vastuCategoryId", "507f1f77bcf86cd799439011", "status", "true")));

        assertEquals("\"status\" must be a boolean", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void addEditZoneRequiresVastuCategoryIdBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addEditZone(Map.of("zone", "red")));

        assertEquals("\"vastuCategoryId\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void addEditZoneRequiresZoneBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addEditZone(Map.of("vastuCategoryId", "507f1f77bcf86cd799439011")));

        assertEquals("\"zone\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void addEditZoneRequiresValidZoneBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addEditZone(Map.of("vastuCategoryId", "507f1f77bcf86cd799439011", "zone", "blue")));

        assertEquals("\"zone\" must be one of [red, green, yellow]", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void getZoneRequiresVastuCategoryIdBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getZone(Map.of("zone", "red")));

        assertEquals("\"vastuCategoryId\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void getZoneRequiresZoneBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getZone(Map.of("vastuCategoryId", "507f1f77bcf86cd799439011")));

        assertEquals("\"zone\" is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void getZoneRequiresValidZoneBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminVastuCompassService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getZone(Map.of("vastuCategoryId", "507f1f77bcf86cd799439011", "zone", "blue")));

        assertEquals("\"zone\" must be one of [red, green, yellow]", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void updateCategoryStripsNonSchemaFieldsFromSet() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.VASTU_COMPASS_CATEGORIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        ObjectId id = new ObjectId("507f1f77bcf86cd799439011");
        when(find.first()).thenReturn(new Document("_id", id), null);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", id).append("title", "New Title").append("image", ""));

        service(mongo).updateVastuCategory(Map.of(
                "vastuCategoryId", "507f1f77bcf86cd799439011",
                "title", "New Title",
                "vastuCompassCategoryImage", "http://cdn/x.png"));

        ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(coll).findOneAndUpdate(any(Bson.class), updateCaptor.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) updateCaptor.getValue()).get("$set");
        assertEquals("New Title", set.get("title"));
        assertEquals("http://cdn/x.png", set.get("image"));
        assertFalse(set.containsKey("vastuCategoryId"));
        assertFalse(set.containsKey("vastuCompassCategoryImage"));
    }

    @Test
    void listCategoryReturnsListAndTotalShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.VASTU_COMPASS_CATEGORIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.skip(anyInt())).thenReturn(find);
        when(find.limit(anyInt())).thenReturn(find);
        when(find.into(any())).thenReturn(new ArrayList<>(List.of(new Document("_id", new ObjectId()).append("image", ""))));
        when(coll.countDocuments(any(Bson.class))).thenReturn(2L);

        Map<String, Object> result = service(mongo).listCategory(Map.of());

        assertEquals(1, ((List<?>) result.get("list")).size());
        assertEquals(2L, result.get("total"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    private AdminVastuCompassService service(MongoTemplate mongo) {
        return new AdminVastuCompassService(mongo, new AdminMongoSupport(mongo),
                mock(MediaUploadService.class), mock(CacheService.class), new AppConstants("bucket", "ap-south-1"));
    }
}
