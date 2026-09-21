package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.BsonValue;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminNotificationMessageServiceTest {

    private static final String OID = "507f1f77bcf86cd799439011";
    private static final String ADMIN = "507f1f77bcf86cd799439012";

    @Test
    void listBuildsFindShapeAndPagination() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId(OID)).append("title", "Hi")));
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(coll.countDocuments(any(Bson.class))).thenReturn(21L);
        AdminNotificationMessageService service = service(mongo);

        Map<String, Object> out = service.list(Map.of("page", "2", "limit", "20", "search", "om"));

        assertEquals(2L, ((Map<?, ?>) out.get("pagination")).get("totalPages"));
        assertEquals(21L, ((Map<?, ?>) out.get("pagination")).get("totalItems"));
        assertTrue(((List<?>) out.get("messages")).get(0) instanceof Document);
    }

    @Test
    void statsPipelinesAreValidJsonAndReturned() {
        AdminNotificationMessageService service = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> service.messageStatsPipeline().forEach(Document::toJson));
        assertDoesNotThrow(() -> service.categoryStatsPipeline().forEach(Document::toJson));
        assertDoesNotThrow(() -> service.simpleCountPipeline("language").forEach(Document::toJson));

        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        AggregateIterable<Document> agg = aggregateReturning(List.of(new Document("_id", "hi").append("count", 1)));
        when(coll.aggregate(anyList())).thenReturn(agg);
        when(coll.countDocuments()).thenReturn(3L);
        when(coll.countDocuments(any(Bson.class))).thenReturn(2L);

        Map<String, Object> out = service(mongo).stats();

        assertEquals(1, ((Document) ((List<?>) out.get("languageStats")).get(0)).get("count"));
        assertEquals(1L, ((Map<?, ?>) out.get("overview")).get("inactiveMessages"));
    }

    @Test
    void categoriesReturnsNodeRouteList() {
        Map<String, Object> out = service(mock(MongoTemplate.class)).categories();
        assertEquals(17, ((List<?>) out.get("data")).size());
        assertFalse(((List<?>) out.get("data")).contains("inactive_user"));
    }

    @Test
    void languagesReturnsTenDocuments() {
        Map<String, Object> out = service(mock(MongoTemplate.class)).languages();
        assertEquals(10, ((List<?>) out.get("data")).size());
    }

    @Test
    void targetAudiencesReturnsFiveValues() {
        Map<String, Object> out = service(mock(MongoTemplate.class)).targetAudiences();
        assertEquals(List.of("all", "new_users", "active_users", "premium_users", "consultants"), out.get("data"));
    }

    @Test
    void getThrowsInvalidMessageIdBeforeFind() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminNotificationMessageService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.get("categories"));

        assertEquals("Invalid message ID", error.getMessage());
    }

    @Test
    void createWhitelistsAndAppliesRouteDefaults() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        AdminNotificationMessageService service = service(mongo);

        service.create(ADMIN, Map.<String, Object>ofEntries(
                Map.entry("title", "  T  "),
                Map.entry("body", "  B  "),
                Map.entry("category", "morning"),
                Map.entry("timing", "9:00"),
                Map.entry("unknown", "drop")));

        ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
        verify(coll).insertOne(doc.capture());
        assertEquals("T", doc.getValue().get("title"));
        assertEquals("hi", doc.getValue().get("language"));
        assertFalse(doc.getValue().containsKey("unknown"));
    }

    @Test
    void updateChecksExistingAndUnsetsFalseyDateFields() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId(OID))));
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", new ObjectId(OID)).append("title", "New"));
        AdminNotificationMessageService service = service(mongo);

        service.update(OID, Map.of("title", " New ", "dayOfMonth", ""));

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document updateDoc = (Document) update.getValue();
        assertEquals("New", ((Document) updateDoc.get("$set")).get("title"));
        assertTrue(((Document) updateDoc.get("$unset")).containsKey("dayOfMonth"));
    }

    @Test
    void deleteThrowsWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.findOneAndDelete(any(Bson.class))).thenReturn(null);
        AdminNotificationMessageService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.delete(OID));

        assertEquals("Message not found", error.getMessage());
    }

    @Test
    void bulkUpdateWhitelistsDataAndReportsCounts() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.updateMany(any(Bson.class), any(Bson.class))).thenReturn(UpdateResult.acknowledged(2L, 1L, (BsonValue) null));
        AdminNotificationMessageService service = service(mongo);

        Map<String, Object> out = service.bulk(Map.of("operation", "update", "messageIds", List.of(OID),
                "data", Map.of("title", "T", "unknown", "drop")));

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(coll).updateMany(any(Bson.class), update.capture());
        assertFalse(((Document) ((Document) update.getValue()).get("$set")).containsKey("unknown"));
        assertEquals(2L, out.get("matchedCount"));
    }

    @Test
    void resetUsageUsesCategoryOnlyWhenPresent() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.updateMany(any(Bson.class), any(Bson.class))).thenReturn(UpdateResult.acknowledged(4L, 3L, (BsonValue) null));
        AdminNotificationMessageService service = service(mongo);

        Map<String, Object> out = service.resetUsage(Map.of("category", "morning"));

        assertEquals(4L, out.get("matchedCount"));
        assertEquals(3L, out.get("modifiedCount"));
        assertNull(out.get("deletedCount"));
    }

    @Test
    void testPreservesUndefinedTwentyFourHoursAgoBug() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId(OID)).append("targetAudience", "new_users")));
        when(coll.find(any(Bson.class))).thenReturn(find);
        AdminNotificationMessageService service = service(mongo);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.test(OID));

        assertEquals("twentyFourHoursAgo is not defined", error.getMessage());
    }

    @Test
    void importMessagesInsertsValidatedDocuments() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        AdminNotificationMessageService service = service(mongo);

        Map<String, Object> out = service.importMessages(ADMIN, Map.of("messages", List.of(Map.of(
                "title", "T", "body", "B", "category", "custom", "timing", "10:00", "priority", "0"))));

        verify(coll).insertMany(anyList());
        assertEquals(1, out.get("imported"));
        assertEquals(1, out.get("total"));
    }

    @Test
    void exportReturnsNodeCsvShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        FindIterable<Document> find = findReturning(List.of(new Document("title", "T").append("body", "B")
                .append("category", "custom").append("timing", "10:00").append("priority", 1)
                .append("language", "hi").append("targetAudience", "all").append("screen", "Home")
                .append("tags", List.of("a", "b")).append("isActive", true)));
        when(coll.find(any(Bson.class))).thenReturn(find);
        AdminNotificationMessageService service = service(mongo);

        String csv = service.export(Map.of("format", "csv"));

        assertTrue(csv.startsWith("title,body,category,timing"));
        assertTrue(csv.contains("\"a, b\""));
    }

    @Test
    void bulkDeleteUsesDeletedCount() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.deleteMany(any(Bson.class))).thenReturn(DeleteResult.acknowledged(2L));
        AdminNotificationMessageService service = service(mongo);

        Map<String, Object> out = service.bulk(Map.of("operation", "delete", "messageIds", List.of(OID)));

        assertEquals(2L, out.get("deletedCount"));
        verify(coll, never()).updateMany(any(Bson.class), any(Bson.class));
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> collection(MongoTemplate mongo) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.NOTIFICATION_MESSAGES)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private static FindIterable<Document> findReturning(List<Document> rows) {
        FindIterable<Document> it = mock(FindIterable.class);
        when(it.sort(any(Bson.class))).thenReturn(it);
        when(it.skip(anyInt())).thenReturn(it);
        when(it.limit(anyInt())).thenReturn(it);
        when(it.first()).thenReturn(rows.isEmpty() ? null : rows.get(0));
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(it).into(any());
        return it;
    }

    @SuppressWarnings("unchecked")
    private static AggregateIterable<Document> aggregateReturning(List<Document> rows) {
        AggregateIterable<Document> it = mock(AggregateIterable.class);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(it).into(any());
        return it;
    }

    private static AdminNotificationMessageService service(MongoTemplate mongo) {
        return new AdminNotificationMessageService(mongo, new AdminMongoSupport(mongo));
    }
}
