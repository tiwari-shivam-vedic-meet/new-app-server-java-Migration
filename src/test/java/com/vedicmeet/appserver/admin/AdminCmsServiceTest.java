package com.vedicmeet.appserver.admin;

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
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminCmsServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";

    // --------------------------------------------------------------- cms doc

    @Test
    void getCmsDetailsReturnsCachedPayloadWithoutHittingMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        Document cached = new Document("title", "About us").append("type", 1);
        when(cache.get(anyString(), eq(Document.class))).thenReturn(cached);

        Object out = service(mongo, cache, mock(PushNotificationService.class)).getCmsDetails(
                Map.of("userType", "user", "type", "1"));

        assertEquals(cached, out);
        verifyNoInteractions(mongo);
    }

    @Test
    void getCmsDetailsFetchesOnMissAndCachesForTwentyFourHours() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        when(cache.get(anyString(), eq(Document.class))).thenReturn(null);
        MongoCollection<Document> coll = mockColl(mongo, Collections.CMS);
        stubFindFirst(coll, new Document("_id", new ObjectId(OID)).append("userType", "user").append("type", 1));

        Object out = service(mongo, cache, mock(PushNotificationService.class)).getCmsDetails(
                Map.of("userType", "user", "type", "1"));

        assertTrue(out instanceof Document);
        assertEquals(OID, ((Document) out).getString("id")); // toJSON id virtual
        // Cache key uses the raw string type; ttl is 24h.
        verify(cache).set(eq("cms:user:1"), any(), eq(24L * 60L * 60L));
    }

    @Test
    void addAndUpdateCmsCreatesWhenAbsentAndInvalidatesCache() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.CMS);
        stubFindFirst(coll, null); // no existing doc -> create path

        Object out = service(mongo, cache, mock(PushNotificationService.class)).addAndUpdateCms(
                Map.of("userType", "user", "type", "1", "title", "T", "description", "D"));

        Map<?, ?> result = (Map<?, ?>) out;
        assertEquals(Boolean.FALSE, result.get("isUpdate"));
        assertTrue(((Document) result.get("cmsData")).containsKey("id"));
        verify(coll).insertOne(any(Document.class));
        verify(cache).invalidate("cms:user:1");
    }

    // ----------------------------------------------------------- add content

    @Test
    void addContentUnknownTypeThrowsContentTypeRequire() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).addContent(Map.of("contentType", "banner")));
        assertEquals("CONTENT_TYPE_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void addContentLanguageRejectsDuplicateTitleBeforeInsert() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.LANGUAGES);
        stubFindFirst(coll, new Document("_id", new ObjectId())); // duplicate title exists

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).addContent(Map.of("contentType", "language", "title", "Hindi")));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(coll, never()).insertOne(any(Document.class));
    }

    @Test
    void addContentSkillsAlwaysThrowsImageRequireAfterTitleCheck() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.SKILLS);
        stubFindFirst(coll, null); // no duplicate title

        // FAITHFUL: the route never carries files, so skills creation is unreachable.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).addContent(Map.of("contentType", "skills", "title", "Tarot")));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(coll, never()).insertOne(any(Document.class));
    }

    @Test
    void addContentNoticeInsertsAndBroadcastsToConsultants() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        PushNotificationService push = mock(PushNotificationService.class);

        MongoCollection<Document> notice = mockColl(mongo, Collections.NOTICE_BOARDS);
        MongoCollection<Document> notif = mockColl(mongo, Collections.NOTIFICATIONS);
        MongoCollection<Document> cons = mockColl(mongo, Collections.CONSULTANTS);
        stubFindInto(cons, List.of(new Document("_id", new ObjectId())
                .append("device", new Document("fcmToken", List.of("t1")))));

        service(mongo, mock(CacheService.class), push).addContent(
                Map.of("contentType", "notice", "userType", "user", "title", "Hi"));

        verify(notice).insertOne(any(Document.class));
        verify(push).sendNotificationAndCons(eq("consultant"), eq("t1"), anyString(), any(), anyString(), anyString());
        verify(notif).insertOne(any(Document.class));
    }

    // ---------------------------------------------------------- edit content

    @Test
    void editContentNoticeThrowsWhenUpdateMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.NOTICE_BOARDS);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).editContent(Map.of("contentType", "notice", "noticeId", OID, "title", "x")));

        assertEquals("NOTICE_NOT_EXIST", error.getMessage());
    }

    @Test
    void editContentFaqThrowsWhenUpdateMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.FAQS);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).editContent(Map.of("contentType", "faq", "faqId", OID, "answer", "a")));

        assertEquals("FAQ_NOT_EXIST", error.getMessage());
    }

    @Test
    void editContentUnknownTypeThrowsContentTypeRequire() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).editContent(Map.of("contentType", "banner")));
        assertEquals("CONTENT_TYPE_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    // ---------------------------------------------------------- list content

    @Test
    void listContentSkillsAppendsSkillImageAndIdVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.SKILLS);
        stubFindList(coll, List.of(new Document("_id", new ObjectId(OID)).append("title", "Tarot").append("image", "a.png")));
        when(coll.countDocuments(any(Bson.class))).thenReturn(2L);

        Object out = service(mongo).listContent(Map.of("contentType", "skills", "userType", "cons"));

        Map<?, ?> result = (Map<?, ?>) out;
        assertEquals(2L, result.get("total"));
        Document row = (Document) ((List<?>) result.get("list")).get(0);
        assertEquals(OID, row.getString("id"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/a.png", row.getString("skillImage"));
    }

    @Test
    void listContentUnknownTypeThrowsContentTypeRequire() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).listContent(Map.of("contentType", "banner", "userType", "cons")));
        assertEquals("CONTENT_TYPE_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    // --------------------------------------------------------- status change

    @Test
    void statusChangeNoticeDeletesDocument() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.NOTICE_BOARDS);

        Object out = service(mongo).statusChangeContent(Map.of("contentType", "notice", "noticeId", OID));

        assertNull(out);
        verify(coll).deleteOne(any(Bson.class));
    }

    @Test
    void statusChangeUnknownTypeIsNoOp() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Object out = service(mongo).statusChangeContent(Map.of("contentType", "banner"));
        assertNull(out); // Node default branch does nothing
        verifyNoInteractions(mongo);
    }

    @Test
    void statusChangeFaqCastsStatusStringToBoolean() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = mockColl(mongo, Collections.FAQS);

        service(mongo).statusChangeContent(Map.of("contentType", "faq", "faqId", OID, "status", "false"));

        org.mockito.ArgumentCaptor<Bson> update = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = ((Document) update.getValue()).get("$set", Document.class);
        assertFalse(set.getBoolean("status")); // "false" string -> boolean false
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> mockColl(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private static void stubFindFirst(MongoCollection<Document> coll, Document first) {
        FindIterable<Document> it = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(first);
    }

    @SuppressWarnings("unchecked")
    private static void stubFindInto(MongoCollection<Document> coll, List<Document> rows) {
        FindIterable<Document> it = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.into(any())).thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private static void stubFindList(MongoCollection<Document> coll, List<Document> rows) {
        FindIterable<Document> it = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.sort(any(Bson.class))).thenReturn(it);
        when(it.skip(anyInt())).thenReturn(it);
        when(it.limit(anyInt())).thenReturn(it);
        when(it.into(any())).thenReturn(rows);
    }

    private AdminCmsService service(MongoTemplate mongo) {
        return service(mongo, mock(CacheService.class), mock(PushNotificationService.class));
    }

    private AdminCmsService service(MongoTemplate mongo, CacheService cache, PushNotificationService push) {
        return new AdminCmsService(mongo, new AdminMongoSupport(mongo), cache, push,
                new AppConstants("bucket", "ap-south-1"));
    }
}
