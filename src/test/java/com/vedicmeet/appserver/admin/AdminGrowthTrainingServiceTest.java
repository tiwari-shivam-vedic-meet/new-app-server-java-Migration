package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminGrowthTrainingServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";

    @Test
    void listGrowthTrainingCategoryPipelineIsValidJsonWithNodeCollections() {
        AdminGrowthTrainingService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.listMedidationCategoryPipeline(new Document("title",
                new Document("$regex", ".*sleep.*").append("$options", "i")), 0, 10);

        assertEquals(6, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        String json = pipeline.toString();
        assertTrue(json.contains("growth_training_medias"));
        assertTrue(json.contains("growthTrainingCategoryId"));
        assertTrue(json.contains("$growthTrainingCategoryId"));
        assertTrue(json.contains("$$growthTrainingCategoryId"));
        assertTrue(json.contains("meditationMedia"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listGrowthTrainingCategoryReturnsListAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminGrowthTrainingService service = service(mongo);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        AggregateIterable<Document> agg = mock(AggregateIterable.class);
        List<Document> rows = List.of(new Document("title", "Training"));
        when(mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)).thenReturn(coll);
        when(coll.aggregate(anyList())).thenReturn(agg);
        when(agg.into(any())).thenReturn(rows);
        when(coll.countDocuments(any(Bson.class))).thenReturn(3L);

        Map<?, ?> result = (Map<?, ?>) service.listMedidationCategory(Map.of("search", ".*"));

        assertEquals(rows, result.get("list"));
        assertEquals(3L, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMediaReturnsListAndTotalAndUsesFindSortNotAggregate() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminGrowthTrainingService service = service(mongo);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        ArrayList<Document> rows = new ArrayList<>(List.of(new Document("_id", new ObjectId(OID)).append("title", "T")));
        when(mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.sort(any(Bson.class))).thenReturn(it);
        when(it.skip(10)).thenReturn(it);
        when(it.limit(5)).thenReturn(it);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(it).into(any());
        when(coll.countDocuments(any(Bson.class))).thenReturn(4L);

        Map<?, ?> result = (Map<?, ?>) service.listMedia(Map.of("growthTrainingCategoryId", " " + OID + " ",
                "page", "3", "limit", "5", "search", "raw.*"));
        Document first = (Document) ((List<?>) result.get("list")).get(0);

        assertEquals("T", first.get("title"));
        assertEquals(OID, first.get("id"));
        assertEquals(4L, result.get("total"));
        ArgumentCaptor<Bson> sort = ArgumentCaptor.forClass(Bson.class);
        verify(it).sort(sort.capture());
        assertEquals(new Document("createdAt", -1), sort.getValue());
        verify(coll, never()).aggregate(anyList());
    }

    @Test
    void listMediaThrowsListTypeErrorForMissingOrInvalidCategoryId() {
        AdminGrowthTrainingService service = service(mock(MongoTemplate.class));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.listMedia(Map.of()));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> service.listMedia(Map.of("growthTrainingCategoryId", "not-object-id")));

        assertEquals("LIST_TYPE_ERROR", missing.getMessage());
        assertEquals("LIST_TYPE_ERROR", invalid.getMessage());
    }

    @Test
    void addGrowthTrainingCategoryRejectsDuplicateTitleBeforeInsertOrCache() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_CATEGORIES,
                new Document("_id", new ObjectId(OID)));
        AdminGrowthTrainingService service = service(mongo, cache, mock(PushNotificationService.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addGrowthTrainingCategory(Map.of("title", "Growth")));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(coll, never()).insertOne(any());
        verifyNoInteractions(cache);
    }

    @Test
    void updateGrowthTrainingCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_CATEGORIES, null);
        AdminGrowthTrainingService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateGrowthTrainingCategory(Map.of("growthTrainingCategoryId", OID)));

        assertEquals("GROWTH_CATEGORY_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void updateGrowthTrainingCategoryRejectsDuplicateTitle() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(new Document("_id", new ObjectId(OID)), new Document("_id", new ObjectId()));
        AdminGrowthTrainingService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateGrowthTrainingCategory(Map.of("growthTrainingCategoryId", OID, "title", "Growth")));

        assertEquals("TITLE_EXIST", error.getMessage());
    }

    @Test
    void blockUnblockCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_CATEGORIES, null);
        AdminGrowthTrainingService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockUnblockCategory(Map.of("growthTrainingCategoryId", OID, "status", false)));

        assertEquals("GROWTH_CATEGORY_NOT_EXIST", error.getMessage());
    }

    @Test
    void getDetailsCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_CATEGORIES, null);
        AdminGrowthTrainingService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getDetailsCategory(Map.of("growthTrainingCategoryId", OID)));

        assertEquals("GROWTH_CATEGORY_NOT_EXIST", error.getMessage());
    }

    @Test
    void addMediaRequiresExistingCategoryBeforeTitleOrInsert() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        PushNotificationService push = mock(PushNotificationService.class);
        collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_CATEGORIES, null);
        AdminGrowthTrainingService service = service(mongo, mock(CacheService.class), push);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addMedia(Map.of("growthTrainingCategoryId", OID, "title", "Session")));

        assertEquals("GROWTH_CATEGORY_NOT_EXIST", error.getMessage());
        verifyNoInteractions(push);
    }

    @Test
    void addMediaRejectsDuplicateTitleBeforeInsertOrNotification() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        PushNotificationService push = mock(PushNotificationService.class);
        collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_CATEGORIES, new Document("_id", new ObjectId(OID)));
        MongoCollection<Document> media = collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_MEDIA,
                new Document("_id", new ObjectId()));
        AdminGrowthTrainingService service = service(mongo, mock(CacheService.class), push);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMedia(Map.of("growthTrainingCategoryId", OID, "title", "Session")));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(media, never()).insertOne(any());
        verifyNoInteractions(push);
    }

    @Test
    void editMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_MEDIA, null);
        AdminGrowthTrainingService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.editMedia(Map.of("growthTrainingMediaId", OID)));

        assertEquals("GROWTH_MEDIA_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void editMediaRejectsDuplicateTitle() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(new Document("_id", new ObjectId(OID)), new Document("_id", new ObjectId()));
        AdminGrowthTrainingService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.editMedia(Map.of("growthTrainingMediaId", OID, "title", "Session")));

        assertEquals("TITLE_EXIST", error.getMessage());
    }

    @Test
    void blockUnblockMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_MEDIA, null);
        AdminGrowthTrainingService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockUnblockMedia(Map.of("growthTrainingMediaId", OID, "status", true)));

        assertEquals("GROWTH_MEDIA_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void detailMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.GROWTH_TRAINING_MEDIA, null);
        AdminGrowthTrainingService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.detailMedia(Map.of("growthTrainingMediaId", OID)));

        assertEquals("GROWTH_MEDIA_NOT_EXIST", error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> collectionReturningFirst(MongoTemplate mongo, String name, Document first) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(first);
        return coll;
    }

    private AdminGrowthTrainingService service(MongoTemplate mongo) {
        return service(mongo, mock(CacheService.class), mock(PushNotificationService.class));
    }

    private AdminGrowthTrainingService service(MongoTemplate mongo, CacheService cache, PushNotificationService push) {
        return new AdminGrowthTrainingService(mongo, new AdminMongoSupport(mongo), cache, push,
                new AppConstants("bucket", "ap-south-1"));
    }
}
