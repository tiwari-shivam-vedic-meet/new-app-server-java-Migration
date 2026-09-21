package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.BsonObjectId;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSystemServiceTest {

    @Test
    void createCouponPersistsHardCodedCouponMasterAndIgnoresBody() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPON_MASTERS);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).createCoupon(Map.of("code", "OTHER", "ignored", true));

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertEquals("FREE5MINUTES", saved.get("code"));
        assertEquals("first_purchase", saved.get("type"));
        assertEquals("time", saved.get("valueType"));
        assertEquals(300, saved.get("value"));
        assertEquals("unlimited", saved.get("usageLimit"));
        assertEquals(true, saved.get("isActive"));
        assertFalse(saved.containsKey("ignored"));
        assertFalse(saved.containsKey("createdAt"));
        assertEquals(saved, out);
    }

    @Test
    @SuppressWarnings("unchecked")
    void couponListBuildsZeroBasedFilterSortAndPagination() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPON_MASTERS);
        FindIterable<Document> find = findReturning(List.of(new Document("code", "FREE5MINUTES")));
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(coll.countDocuments(any(Bson.class))).thenReturn(13L);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> sort = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).couponList(Map.of(
                "page", "2", "pageSize", "5",
                "filterField", "code", "filterOperator", "startsWith", "filterValue", "FREE",
                "sortField", "value", "sortDirection", "desc"));

        verify(coll).find(filter.capture());
        verify(find).sort(sort.capture());
        verify(find).skip(10);
        verify(find).limit(5);
        Document filterDoc = (Document) filter.getValue();
        assertEquals(new Document("$regex", "^FREE").append("$options", "i"), filterDoc.get("code"));
        assertEquals(new Document("value", -1), sort.getValue());
        Map<String, Object> pagination = (Map<String, Object>) out.get("pagination");
        assertEquals(3, pagination.get("pageCount"));
        assertEquals(2, pagination.get("pageNumber"));
        assertEquals(13L, pagination.get("totalDocuments"));
        assertEquals(1, ((List<?>) out.get("list")).size());
    }

    @Test
    void couponListLeavesEmptySortAndFilterForNullSentinels() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPON_MASTERS);
        FindIterable<Document> find = findReturning(List.of());
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(coll.countDocuments(any(Bson.class))).thenReturn(0L);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> sort = ArgumentCaptor.forClass(Bson.class);

        service(mongo).couponList(Map.of("sortField", "null", "sortDirection", "asc"));

        verify(coll).find(filter.capture());
        verify(find).sort(sort.capture());
        assertEquals(new Document(), filter.getValue());
        assertEquals(new Document(), sort.getValue());
        verify(find).skip(0);
        verify(find).limit(1000);
    }

    @Test
    void notificationUsesHardCodedPayloadAndReportsSendResult() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        PushNotificationService push = mock(PushNotificationService.class);
        when(push.send(eq("user"), any(), eq("Incoming Call"), eq("Test message"), anyMap(), eq(null), eq(true), anyMap()))
                .thenReturn(true);
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);

        Map<String, Object> out = service(mongo, push).notification(Map.of("token", "ignored"));

        verify(push).send(eq("user"), eq("eH278FeQRbqNWk5CbIudJF:APA91bGNkoX5gcUCAtvmfkjT90wP5py7kV56XerFZUySdUiHPjIdRwHzdL_zC_kar0rtZMw7B0l6stlRjpm-ym1MpFa1NTcWRik6yi2srf2AZCfbzuu65fo"),
                eq("Incoming Call"), eq("Test message"), data.capture(), eq(null), eq(true), anyMap());
        assertEquals("incoming_call", data.getValue().get("type"));
        assertEquals("1234567890", data.getValue().get("callId"));
        assertEquals(Map.of("success", true, "messageId", true), out);
    }

    @Test
    void availabilityReadsHardCodedConsultantAndReturnsWholeDocument() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        Document consultant = new Document("_id", new ObjectId("67d72b41f5719fb28799c01e"))
                .append("availability", Map.of("monday", List.of()));
        FindIterable<Document> find = findReturningFirst(consultant);
        when(coll.find(any(Bson.class))).thenReturn(find);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).availability(Map.of("consultantId", "ignored"));

        verify(coll).find(filter.capture());
        assertEquals(new ObjectId("67d72b41f5719fb28799c01e"), ((Document) filter.getValue()).get("_id"));
        assertEquals(consultant, out);
    }

    @Test
    void availabilityPreservesNullConsultantAvailabilityTypeError() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        FindIterable<Document> find = findReturningFirst(null);
        when(coll.find(any(Bson.class))).thenReturn(find);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).availability(Map.of()));

        assertEquals("Cannot read properties of null (reading 'availability')", error.getMessage());
    }

    @Test
    void masterGetFindsSeedMasterByDocumentQueryParam() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SEED_MASTERS);
        Document master = new Document("for", "consultantMasterSettings").append("data", Map.of("x", 1));
        FindIterable<Document> find = findReturningFirst(master);
        when(coll.find(any(Bson.class))).thenReturn(find);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).masterGet(Map.of("document", "consultantMasterSettings"));

        verify(coll).find(filter.capture());
        assertEquals(new Document("for", "consultantMasterSettings"), filter.getValue());
        assertEquals(master, out);
    }

    @Test
    @SuppressWarnings("unchecked")
    void masterUpdateUpsertsOnlyAllowedDocumentsAndReturnsMongoResultEnvelope() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SEED_MASTERS);
        ObjectId upsertedId = new ObjectId();
        when(coll.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, new BsonObjectId(upsertedId)));
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<UpdateOptions> options = ArgumentCaptor.forClass(UpdateOptions.class);

        Map<String, Object> out = service(mongo).masterUpdate(Map.of(
                "document", "firstFreeConsultantForAndroid",
                "updatedData", Map.of("consultantIds", List.of("c1"))));

        verify(coll).updateOne(filter.capture(), update.capture(), options.capture());
        assertEquals(new Document("for", "firstFreeConsultantForAndroid"), filter.getValue());
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertEquals(Map.of("consultantIds", List.of("c1")), set.get("data"));
        assertTrue(options.getValue().isUpsert());
        assertEquals("Document created successfully", out.get("message"));
        Document data = (Document) out.get("data");
        assertEquals(1, data.get("upsertedCount"));
        assertEquals(upsertedId, data.get("upsertedId"));
    }

    @Test
    void masterUpdateRejectsPrimitiveOrMissingUpdatedDataBeforeWrite() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SEED_MASTERS);

        IllegalArgumentException primitive = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).masterUpdate(Map.of("document", "consultantMasterSettings", "updatedData", "bad")));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).masterUpdate(Map.of("document", "consultantMasterSettings")));

        assertEquals("Data is not valid!", primitive.getMessage());
        assertEquals("Data is not valid!", missing.getMessage());
        verify(coll, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @Test
    void masterUpdateUnknownDocumentIsSuccessfulNoOpWithNoDataKey() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SEED_MASTERS);

        Map<String, Object> out = service(mongo).masterUpdate(Map.of("document", "unknown", "updatedData", Map.of()));

        assertEquals("Document updated successfully", out.get("message"));
        assertFalse(out.containsKey("data"));
        verify(coll, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> findReturning(List<Document> rows) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.skip(anyInt())).thenReturn(find);
        when(find.limit(anyInt())).thenReturn(find);
        when(find.into(any())).thenReturn(new ArrayList<>(rows));
        return find;
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> findReturningFirst(Document first) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(find.first()).thenReturn(first);
        return find;
    }

    private AdminSystemService service(MongoTemplate mongo) {
        return new AdminSystemService(mongo, new AdminMongoSupport(mongo));
    }

    private AdminSystemService service(MongoTemplate mongo, PushNotificationService push) {
        return new AdminSystemService(mongo, new AdminMongoSupport(mongo), push);
    }
}
