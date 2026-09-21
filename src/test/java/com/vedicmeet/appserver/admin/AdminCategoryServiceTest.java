package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminCategoryServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";

    // ------------------------------------------------------------- pipelines

    @Test
    void listCategoryPipelineIsValidJsonWithFiveStages() {
        AdminCategoryService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.listCategoryPipeline(new Document("status", true), 0, 10);

        assertEquals(5, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        // MEDIA_URL is concatenated into the categoryImage projection.
        assertTrue(pipeline.get(1).toJson().contains("https://bucket.s3.ap-south-1.amazonaws.com/"));
    }

    @Test
    void listMediaCategoryPipelineIsValidJsonWithSevenStages() {
        AdminCategoryService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.listMediaCategoryPipeline(
                new Document("categoryId", new ObjectId(OID)), 0, 10);

        assertEquals(7, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        // $lookup joins the categories collection.
        assertTrue(pipeline.get(1).toJson().contains("\"from\": \"categories\""));
    }

    // ------------------------------------------------------------- list reads

    @Test
    @SuppressWarnings("unchecked")
    void listCategoryReturnsListAndTotalAndCachesResult() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        AdminCategoryService service = new AdminCategoryService(mongo, new AdminMongoSupport(mongo),
                mock(MediaUploadService.class), cache, new AppConstants("bucket", "ap-south-1"));

        MongoCollection<Document> coll = mock(MongoCollection.class);
        AggregateIterable<Document> agg = mock(AggregateIterable.class);
        List<Document> rows = List.of(new Document("title", "Love"));
        when(mongo.getCollection(Collections.CATEGORIES)).thenReturn(coll);
        when(coll.aggregate(anyList())).thenReturn(agg);
        when(agg.into(any())).thenReturn(rows);
        when(coll.countDocuments(any(Bson.class))).thenReturn(3L);
        when(cache.get(anyString(), eq(Document.class))).thenReturn(null);

        Object out = service.listCategory(Map.of());

        assertTrue(out instanceof Map);
        Map<?, ?> result = (Map<?, ?>) out;
        assertEquals(rows, result.get("list"));
        assertEquals(3L, result.get("total"));
        // No search + no status -> cached under the 'all' key for 1h.
        verify(cache).set(eq("category:list:all:1:10"), any(), eq(3600L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listCategoryReturnsCachedPayloadWithoutHittingMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        AdminCategoryService service = new AdminCategoryService(mongo, new AdminMongoSupport(mongo),
                mock(MediaUploadService.class), cache, new AppConstants("bucket", "ap-south-1"));

        Document cached = new Document("list", List.of()).append("total", 0);
        when(cache.get(anyString(), eq(Document.class))).thenReturn(cached);

        Object out = service.listCategory(Map.of());

        assertEquals(cached, out);
        verifyNoInteractions(mongo);
    }

    @Test
    void listMediaCategoryRejectsInvalidCategoryIdBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);

        assertThrows(IllegalArgumentException.class,
                () -> service.listMediaCategory(Map.of("categoryId", "not-a-valid-id")));
        verifyNoInteractions(mongo);
    }

    // ------------------------------------------------------------- write guards

    @Test
    void addCategoryRequiresImageAfterTitleCheck() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminCategoryService service = service(mongo, uploads);
        collectionReturningFirst(mongo, Collections.CATEGORIES, null); // no duplicate title

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addCategory(Map.of("title", "Love"), null));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void addCategoryRejectsDuplicateTitleBeforeUpload() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminCategoryService service = service(mongo, uploads);
        collectionReturningFirst(mongo, Collections.CATEGORIES, new Document("_id", new ObjectId()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addCategory(Map.of("title", "Love"), mock(org.springframework.web.multipart.MultipartFile.class)));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void updateCategoryThrowsNodeLiteralTitleExistWhenRecordMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);
        collectionReturningFirst(mongo, Collections.CATEGORIES, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateCategory(Map.of("toUpdateId", OID), null));

        assertEquals("TITLE_EXIST", error.getMessage());
    }

    @Test
    void blockUnblockCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);
        collectionReturningFirst(mongo, Collections.CATEGORIES, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockUnblock(Map.of("type", "category", "categoryId", OID, "status", true)));

        assertEquals("CATEGORY_NOT_EXIST", error.getMessage());
    }

    @Test
    void blockUnblockMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);
        collectionReturningFirst(mongo, Collections.CATEGORIES_MUSIC, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockUnblock(Map.of("type", "media", "categoryMediaId", OID, "status", false)));

        assertEquals("CATEGORY_MEDIA_NOT_EXIST", error.getMessage());
    }

    @Test
    void blockUnblockUnknownTypeIsNoOp() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);

        Object out = service.blockUnblock(Map.of("type", "other", "status", true));

        assertNull(out); // Node default branch returns undefined
        verifyNoInteractions(mongo);
    }

    @Test
    void addMusicOrMantraRequiresExistingCategory() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);
        collectionReturningFirst(mongo, Collections.CATEGORIES, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addMusicOrMantra(Map.of("categoryId", OID, "description", "d", "type", "t"), null));

        assertEquals("CATEGORY_NOT_EXIST", error.getMessage());
    }

    @Test
    void addMusicOrMantraRequiresMediaAfterCategoryCheck() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminCategoryService service = service(mongo, uploads);
        collectionReturningFirst(mongo, Collections.CATEGORIES, new Document("_id", new ObjectId(OID)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMusicOrMantra(Map.of("categoryId", OID, "description", "d", "type", "t"), null));

        assertEquals("MEDIA_REQUIRE", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void updateMusicOrMantraThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminCategoryService service = service(mongo);
        collectionReturningFirst(mongo, Collections.CATEGORIES_MUSIC, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateMusicOrMantra(Map.of("categoryMediaId", OID), null));

        assertEquals("CATEGORY_MEDIA_NOT_EXIST", error.getMessage());
    }

    // ------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static void collectionReturningFirst(MongoTemplate mongo, String name, Document first) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(first);
    }

    private AdminCategoryService service(MongoTemplate mongo) {
        return service(mongo, mock(MediaUploadService.class));
    }

    private AdminCategoryService service(MongoTemplate mongo, MediaUploadService uploads) {
        return new AdminCategoryService(mongo, new AdminMongoSupport(mongo), uploads,
                mock(CacheService.class), new AppConstants("bucket", "ap-south-1"));
    }
}
