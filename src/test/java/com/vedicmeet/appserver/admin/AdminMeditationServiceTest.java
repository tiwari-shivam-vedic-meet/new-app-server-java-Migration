package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminMeditationServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";

    @Test
    void listMeditationCategoryPipelineIsValidJsonWithNodeCollections() {
        AdminMeditationService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.listMedidationCategoryPipeline(new Document("title",
                new Document("$regex", ".*sleep.*").append("$options", "i")), 0, 10);

        assertEquals(6, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        assertTrue(pipeline.get(1).toJson().contains("\"from\": \"meditation_medias\""));
        assertTrue(pipeline.get(2).toJson().contains("https://bucket.s3.ap-south-1.amazonaws.com/"));
    }

    @Test
    void userMeditationHistoryPipelineContainsFaithfulLookupsAndTimeBounds() {
        AdminMeditationService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.userMeditationHistoryListPipeline(Map.of(
                "search", " u ",
                "filter", Map.of("timeOfDay", "morning", "meditationType", "Music")), 5, 10);

        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        String json = pipeline.toString();
        assertTrue(json.contains("meditation_medias"));
        assertTrue(json.contains("users"));
        assertTrue(json.contains("Asia/Kolkata"));
        assertTrue(json.contains("$exists=true"));
        assertTrue(json.contains("status=true"));
        assertTrue(json.contains("$gte=[Document{{$hour=Document{{date=$createdAt, timezone=Asia/Kolkata}}}}, 6]"));
        assertTrue(json.contains("$lte=[Document{{$hour=Document{{date=$createdAt, timezone=Asia/Kolkata}}}}, 11]"));
        assertTrue(json.contains("meditationType=Music"));
        assertTrue(json.contains("userNameId"));
    }

    @Test
    void userMeditationHistoryNightPipelineUsesOrBounds() {
        AdminMeditationService service = service(mock(MongoTemplate.class));
        Document match = service.userHistoryMatchConditions(Map.of("filter", Map.of("timeOfDay", "night")));

        assertDoesNotThrow(() -> match.toJson());
        String json = match.toJson();
        assertTrue(json.contains("\"$or\""));
        assertTrue(json.contains("22"));
        assertTrue(json.contains("5"));
        assertTrue(json.contains("Asia/Kolkata"));
    }

    @Test
    void meditationInsightsPipelineContainsNodeCollectionsAndRanges() {
        AdminMeditationService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.getMeditationInsightsPipeline(Map.of("timeOfDay", "evening", "meditationType", "Other"));

        assertEquals(8, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        String json = pipeline.toString();
        assertTrue(json.contains("meditation_medias"));
        assertTrue(json.contains("$exists=true"));
        assertTrue(json.contains("status=true"));
        assertTrue(json.contains("$gte=[Document{{$hour=$createdAt}}, 18]"));
        assertTrue(json.contains("$lte=[Document{{$hour=$createdAt}}, 23]"));
        assertTrue(json.contains("meditation.type=0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMeditationCategoryReturnsListAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminMeditationService service = service(mongo);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        AggregateIterable<Document> agg = mock(AggregateIterable.class);
        List<Document> rows = List.of(new Document("title", "Calm"));
        when(mongo.getCollection(Collections.MEDITATION_CATEGORY)).thenReturn(coll);
        when(coll.aggregate(anyList())).thenReturn(agg);
        when(agg.into(any())).thenReturn(rows);
        when(coll.countDocuments(any(Bson.class))).thenReturn(3L);

        Object out = service.listMedidationCategory(Map.of());

        Map<?, ?> result = (Map<?, ?>) out;
        assertEquals(rows, result.get("list"));
        assertEquals(3L, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMeditationMediaReturnsListAndTotalWithVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminMeditationService service = service(mongo);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        ArrayList<Document> rows = new ArrayList<>(List.of(new Document("_id", new ObjectId(OID))
                .append("image", "img.jpg").append("video", "v.gif").append("mantra", "m.mp3").append("music", "song.mp3")));
        when(mongo.getCollection(Collections.MEDITATION_MEDIA)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.sort(any(Bson.class))).thenReturn(it);
        when(it.skip(0)).thenReturn(it);
        when(it.limit(10)).thenReturn(it);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(it).into(any());
        when(coll.countDocuments(any(Bson.class))).thenReturn(4L);

        Map<?, ?> result = (Map<?, ?>) service.listMeditationMedia(Map.of("meditationCategoryId", OID));
        Document first = (Document) ((List<?>) result.get("list")).get(0);

        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/img.jpg", first.get("meditationImage"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/v.gif", first.get("meditationVideo"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/m.mp3", first.get("meditationMantra"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/song.mp3", first.get("meditationMusic"));
        assertEquals(4L, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void userMeditationHistoryReturnsListAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminMeditationService service = service(mongo);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        AggregateIterable<Document> listAgg = mock(AggregateIterable.class);
        AggregateIterable<Document> countAgg = mock(AggregateIterable.class);
        List<Document> rows = List.of(new Document("userName", "A"));
        when(mongo.getCollection(Collections.MEDIA_RECENTS)).thenReturn(coll);
        when(coll.aggregate(anyList())).thenReturn(listAgg, countAgg);
        when(listAgg.into(any())).thenReturn(rows);
        when(countAgg.into(any())).thenReturn(List.of(new Document("count", 7)));

        Map<?, ?> result = (Map<?, ?>) service.userListMeditationHistory(Map.of());

        assertEquals(rows, result.get("list"));
        assertEquals(7, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void meditationInsightsReturnsDefaultShapeWhenNoRows() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminMeditationService service = service(mongo);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        AggregateIterable<Document> agg = mock(AggregateIterable.class);
        when(mongo.getCollection(Collections.MEDIA_RECENTS)).thenReturn(coll);
        when(coll.aggregate(anyList())).thenReturn(agg);
        when(agg.into(any())).thenReturn(List.of());

        Map<?, ?> result = (Map<?, ?>) service.getMeditationInsights(Map.of());

        assertEquals(0, result.get("totalUsers"));
        assertEquals(0, result.get("repeatUsers"));
        assertTrue(result.containsKey("categoryMetrics"));
    }

    @Test
    void addMeditationCategoryRejectsDuplicateTitleBeforeUploadOrInsert() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, new Document("_id", new ObjectId(OID)));
        AdminMeditationService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMeditationCategory(Map.of("title", "Sleep"), mock(MultipartFile.class)));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void addMeditationCategoryRequiresImageAfterTitleCheck() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, null);
        AdminMeditationService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMeditationCategory(Map.of("title", "Sleep"), null));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void updateMeditationCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, null);
        AdminMeditationService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateMeditationCategory(Map.of("meditationCategoryId", OID), null));

        assertEquals("MEDITATION_CATEGORY_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void blockMeditationCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, null);
        AdminMeditationService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockUnblockMeditationCategory(Map.of("meditationCategoryId", OID, "status", true)));

        assertEquals("MEDITATION_CATEGORY_NOT_EXIST", error.getMessage());
    }

    @Test
    void getMeditationCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, null);
        AdminMeditationService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMeditationCategory(Map.of("meditationCategoryId", OID)));

        assertEquals("MEDITATION_CATEGORY_NOT_EXIST", error.getMessage());
    }

    @Test
    void addMeditationMediaRequiresExistingCategoryBeforeTitleOrUploads() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, null);
        AdminMeditationService service = service(mongo, uploads);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addMeditationMedia(Map.of("meditationCategoryId", OID, "title", "Song"), null, null, null, null));

        assertEquals("MEDITATION_CATEGORY_NOT_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void addMeditationMediaRejectsDuplicateTitleBeforeUpload() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MEDITATION_CATEGORY, new Document("_id", new ObjectId(OID)));
        collectionReturningFirst(mongo, Collections.MEDITATION_MEDIA, new Document("_id", new ObjectId()));
        AdminMeditationService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMeditationMedia(Map.of("meditationCategoryId", OID, "title", "Song"),
                        mock(MultipartFile.class), null, null, null));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void editMeditationMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MEDITATION_MEDIA, null);
        AdminMeditationService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.editMeditationMedia(Map.of("meditationMediaId", OID), null, null, null, null));

        assertEquals("MEDITATION_MEDIA_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void editMeditationMediaRejectsDuplicateTitle() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(Collections.MEDITATION_MEDIA)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(new Document("_id", new ObjectId(OID)), new Document("_id", new ObjectId()));
        AdminMeditationService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.editMeditationMedia(Map.of("meditationMediaId", OID, "title", "Song"), null, null, null, null));

        assertEquals("TITLE_EXIST", error.getMessage());
    }

    @Test
    void blockMeditationMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MEDITATION_MEDIA, null);
        AdminMeditationService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockMeditationMedia(Map.of("meditationMediaId", OID, "status", false)));

        assertEquals("MEDITATION_MEDIA_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void serviceDoesNotTouchCleverTapForAdminRoutes() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CleverTapClient cleverTap = mock(CleverTapClient.class);
        AdminMeditationService service = new AdminMeditationService(mongo, new AdminMongoSupport(mongo),
                mock(MediaUploadService.class), mock(CacheService.class), cleverTap, new AppConstants("bucket", "ap-south-1"));
        collectionReturningFirst(mongo, Collections.MEDITATION_MEDIA, null);

        assertThrows(IllegalStateException.class,
                () -> service.getMeditationMedia(Map.of("meditationMediaId", OID)));
        verifyNoInteractions(cleverTap);
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

    private AdminMeditationService service(MongoTemplate mongo) {
        return service(mongo, mock(MediaUploadService.class));
    }

    private AdminMeditationService service(MongoTemplate mongo, MediaUploadService uploads) {
        return new AdminMeditationService(mongo, new AdminMongoSupport(mongo), uploads,
                mock(CacheService.class), mock(CleverTapClient.class), new AppConstants("bucket", "ap-south-1"));
    }
}
