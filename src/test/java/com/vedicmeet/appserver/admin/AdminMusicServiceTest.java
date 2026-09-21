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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminMusicServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";

    // ------------------------------------------------------------- pipelines

    @Test
    void listMusicCategoryPipelineIsValidJsonWithSixStages() {
        AdminMusicService service = service(mock(MongoTemplate.class));
        List<Document> pipeline = service.listMusicCategoryPipeline(new Document("title",
                new Document("$regex", ".*sleep.*").append("$options", "i")), 0, 10);

        assertEquals(6, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        assertTrue(pipeline.get(1).toJson().contains("\"from\": \"music_medias\""));
        assertTrue(pipeline.get(2).toJson().contains("https://bucket.s3.ap-south-1.amazonaws.com/"));
    }

    // ------------------------------------------------------------- list reads

    @Test
    @SuppressWarnings("unchecked")
    void listMusicCategoryReturnsListAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminMusicService service = service(mongo);

        MongoCollection<Document> coll = mock(MongoCollection.class);
        AggregateIterable<Document> agg = mock(AggregateIterable.class);
        List<Document> rows = List.of(new Document("title", "Sleep"));
        when(mongo.getCollection(Collections.MUSICS)).thenReturn(coll);
        when(coll.aggregate(anyList())).thenReturn(agg);
        when(agg.into(any())).thenReturn(rows);
        when(coll.countDocuments(any(Bson.class))).thenReturn(2L);

        Object out = service.listMusicCategory(Map.of());

        assertTrue(out instanceof Map);
        Map<?, ?> result = (Map<?, ?>) out;
        assertEquals(rows, result.get("list"));
        assertEquals(2L, result.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMusicMediaReturnsListAndTotalWithVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminMusicService service = service(mongo);

        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        ArrayList<Document> rows = new ArrayList<>(List.of(new Document("_id", new ObjectId(OID))
                .append("image", "img.jpg").append("music", "song.mp3")));
        when(mongo.getCollection(Collections.MUSIC_MEDIA)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.sort(any(Bson.class))).thenReturn(it);
        when(it.skip(0)).thenReturn(it);
        when(it.limit(10)).thenReturn(it);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(it).into(any());
        when(coll.countDocuments(any(Bson.class))).thenReturn(4L);

        Object out = service.listMusicMedia(Map.of("musicId", OID));

        Map<?, ?> result = (Map<?, ?>) out;
        List<?> list = (List<?>) result.get("list");
        Document first = (Document) list.get(0);
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/img.jpg", first.get("musicImage"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/song.mp3", first.get("musicMedia"));
        assertEquals(4L, result.get("total"));
    }

    // ------------------------------------------------------------- write guards

    @Test
    void addMusicCategoryRejectsDuplicateTitleBeforeUploadOrInsert() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MUSICS,
                new Document("_id", new ObjectId(OID)));
        AdminMusicService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMusicCategory(Map.of("title", "Sleep"), mock(MultipartFile.class)));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void addMusicCategoryRequiresImageAfterTitleCheck() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MUSICS, null);
        AdminMusicService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMusicCategory(Map.of("title", "Sleep"), null));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void blockMusicCategoryThrowsWhenMissingBeforeUpdate() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MUSICS, null);
        AdminMusicService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockMusicCategory(Map.of("musicId", OID, "status", true)));

        assertEquals("MUSIC_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void getDetailMusicCategoryThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.MUSICS, null);
        AdminMusicService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getDetailMusicCategory(Map.of("musicId", OID)));

        assertEquals("MUSIC_NOT_EXIST", error.getMessage());
    }

    @Test
    void addMusicMediaRequiresExistingMusicBeforeOtherChecks() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MUSICS, null);
        AdminMusicService service = service(mongo, uploads);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addMusicMedia(Map.of("musicId", OID, "title", "Song", "musicDuration", "1"), null, null));

        assertEquals("MUSIC_NOT_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void addMusicMediaRejectsDuplicateTitleBeforeUpload() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MUSICS, new Document("_id", new ObjectId(OID)));
        collectionReturningFirst(mongo, Collections.MUSIC_MEDIA, new Document("_id", new ObjectId()));
        AdminMusicService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMusicMedia(Map.of("musicId", OID, "title", "Song", "musicDuration", "1"),
                        mock(MultipartFile.class), mock(MultipartFile.class)));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void addMusicMediaRequiresImageBeforeMedia() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        collectionReturningFirst(mongo, Collections.MUSICS, new Document("_id", new ObjectId(OID)));
        collectionReturningFirst(mongo, Collections.MUSIC_MEDIA, null);
        AdminMusicService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMusicMedia(Map.of("musicId", OID, "title", "Song", "musicDuration", "1"),
                        null, mock(MultipartFile.class)));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(uploads, never()).upload(any(), anyString());
    }

    @Test
    void addMusicMediaRequiresMediaAfterImage() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(uploads.upload(image, "music")).thenReturn("img.jpg");
        collectionReturningFirst(mongo, Collections.MUSICS, new Document("_id", new ObjectId(OID)));
        collectionReturningFirst(mongo, Collections.MUSIC_MEDIA, null);
        AdminMusicService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addMusicMedia(Map.of("musicId", OID, "title", "Song", "musicDuration", "1"), image, null));

        assertEquals("MEDIA_REQUIRE", error.getMessage());
    }

    @Test
    void editMusicMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MUSIC_MEDIA, null);
        AdminMusicService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.editMusicMedia(Map.of("musicMediaId", OID), null, null));

        assertEquals("MUSIC_MEDIA_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void editMusicMediaRejectsDuplicateTitle() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(Collections.MUSIC_MEDIA)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(new Document("_id", new ObjectId(OID)),
                new Document("_id", new ObjectId()));
        AdminMusicService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.editMusicMedia(Map.of("musicMediaId", OID, "title", "Song"), null, null));

        assertEquals("TITLE_EXIST", error.getMessage());
    }

    @Test
    void getDetailMusicMediaThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, Collections.MUSIC_MEDIA, null);
        AdminMusicService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getDetailMusicMedia(Map.of("musicMediaId", OID)));

        assertEquals("MUSIC_MEDIA_NOT_EXIST", error.getMessage());
    }

    @Test
    void blockMusicMediaThrowsWhenMissingBeforeUpdate() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.MUSIC_MEDIA, null);
        AdminMusicService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.blockMusicMedia(Map.of("musicMediaId", OID, "status", false)));

        assertEquals("MUSIC_MEDIA_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void serviceDoesNotTouchCleverTapForAdminRoutes() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CleverTapClient cleverTap = mock(CleverTapClient.class);
        AdminMusicService service = new AdminMusicService(mongo, new AdminMongoSupport(mongo),
                mock(MediaUploadService.class), mock(CacheService.class), cleverTap, new AppConstants("bucket", "ap-south-1"));
        collectionReturningFirst(mongo, Collections.MUSICS, null);

        assertThrows(IllegalStateException.class,
                () -> service.getDetailMusicCategory(Map.of("musicId", OID)));
        verifyNoInteractions(cleverTap);
    }

    // ------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> collectionReturningFirst(MongoTemplate mongo, String name, Document first) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable<Document> it = mock(FindIterable.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(first);
        return coll;
    }

    private AdminMusicService service(MongoTemplate mongo) {
        return service(mongo, mock(MediaUploadService.class));
    }

    private AdminMusicService service(MongoTemplate mongo, MediaUploadService uploads) {
        return new AdminMusicService(mongo, new AdminMongoSupport(mongo), uploads,
                mock(CacheService.class), mock(CleverTapClient.class), new AppConstants("bucket", "ap-south-1"));
    }
}
