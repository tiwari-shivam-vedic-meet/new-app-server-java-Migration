package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminNotificationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void listUsesInstantFilterUnescapedSearchAndAddsVirtualId() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SCHEDULED_NOTIFICATIONS);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId()).append("title", "Sale")));
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(coll.countDocuments(any(Bson.class))).thenReturn(1L);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).list(Map.of("page", "2", "limit", "5", "search", "a+b"));

        verify(coll).find(filter.capture());
        Document params = (Document) filter.getValue();
        assertEquals("instant", params.get("notificationType"));
        assertEquals(new Document("$regex", ".*a+b.*").append("$options", "i"), params.get("title"));
        assertEquals(1L, out.get("total"));
        Document first = (Document) ((List<?>) out.get("list")).get(0);
        assertNotNull(first.get("id"));
    }

    @Test
    void sendPersistsScheduledNotificationWhitelistDefaultsAndLeavesTransportNoop() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SCHEDULED_NOTIFICATIONS);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).send(Map.of(
                "title", "  Hi  ",
                "message", "  Body  ",
                "userType", "all",
                "ignored", "drop"));

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertEquals("Hi", saved.get("title"));
        assertEquals("Body", saved.get("message"));
        assertEquals("all", saved.get("userType"));
        assertEquals("all", saved.get("activeUserType"));
        assertEquals("instant", saved.get("notificationType"));
        assertEquals("scheduled", saved.get("status"));
        assertFalse(saved.containsKey("ignored"));
        assertNotNull(saved.get("createdAt"));
        assertEquals("noop", out.get("transport"));
        assertEquals("instant-send", out.get("operation"));
        assertNotNull(out.get("notificationId"));
    }

    @Test
    void deleteThrowsWhenNotificationMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.NOTIFICATIONS);
        FindIterable<Document> find = findReturning(List.of());
        when(coll.find(any(Bson.class))).thenReturn(find);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).delete(Map.of("notificationId", "507f1f77bcf86cd799439011")));

        assertEquals("NOTIFICATION_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndDelete(any(Bson.class));
    }

    @Test
    void deleteReturnsEmptyResultAfterFindOneAndDelete() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.NOTIFICATIONS);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId())));
        when(coll.find(any(Bson.class))).thenReturn(find);

        Map<String, Object> out = service(mongo).delete(Map.of("notificationId", "507f1f77bcf86cd799439011"));

        verify(coll).findOneAndDelete(any(Bson.class));
        assertEquals(Map.of(), out);
    }

    @Test
    void adminNotificationReturnsFacetListAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.NOTIFICATIONS);
        Document facet = new Document("list", List.of(new Document("_id", new ObjectId()).append("title", "Admin")))
                .append("count", List.of(new Document("total", 7)));
        AggregateIterable<Document> aggregate = aggregateReturning(List.of(facet));
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).adminNotification(Map.of("page", "1", "limit", "10"));

        verify(coll).aggregate(pipeline.capture());
        assertEquals(3, pipeline.getValue().size());
        assertEquals(7L, out.get("total"));
        Document first = (Document) ((List<?>) out.get("list")).get(0);
        assertEquals("Admin", first.get("title"));
        assertNotNull(first.get("id"));
    }

    @Test
    void readNotificationPreservesMissingUserArgumentBugWhenDocumentExists() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.NOTIFICATIONS);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId())));
        when(coll.find(any(Bson.class))).thenReturn(find);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).readNotification(Map.of("notificationId", "507f1f77bcf86cd799439011")));

        assertEquals("Cannot read properties of undefined (reading '_id')", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class));
    }

    @Test
    void readNotificationReturnsNullWhenDocumentMissingBeforeUserBug() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.NOTIFICATIONS);
        FindIterable<Document> find = findReturning(List.of());
        when(coll.find(any(Bson.class))).thenReturn(find);

        assertNull(service(mongo).readNotification(Map.of("notificationId", "507f1f77bcf86cd799439011")));
    }

    @Test
    void countPreservesUndefinedNotificationCountDeadEndpoint() {
        MongoTemplate mongo = mock(MongoTemplate.class);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).count(Map.of()));

        assertEquals("NotificationService.notificationCount is not a function", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void scheduledUsesAggregationPipelineAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SCHEDULED_NOTIFICATIONS);
        AggregateIterable<Document> aggregate = aggregateReturning(List.of(new Document("_id", new ObjectId()).append("title", "Later")));
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        when(coll.countDocuments(any(Bson.class))).thenReturn(3L);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).scheduled(Map.of("page", "2", "limit", "2", "search", "x.y"));

        verify(coll).aggregate(pipeline.capture());
        Document match = (Document) ((Document) pipeline.getValue().get(0)).get("$match");
        assertEquals("scheduled", match.get("notificationType"));
        assertEquals(new Document("$regex", ".*x.y.*").append("$options", "i"), match.get("title"));
        assertEquals(3L, out.get("total"));
        assertEquals("Later", ((Document) ((List<?>) out.get("list")).get(0)).get("title"));
    }

    @Test
    void scheduleAndCancelAreTransportNoopsWithoutMongoWrites() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminNotificationService service = service(mongo);

        Map<String, Object> scheduled = service.schedule(Map.of("title", "T", "message", "M", "userType", "user"));
        Map<String, Object> cancelled = service.cancelScheduled(Map.of("notificationId", "abc"));

        assertEquals("noop", scheduled.get("transport"));
        assertEquals("schedule", scheduled.get("operation"));
        assertEquals("noop", cancelled.get("transport"));
        assertEquals("scheduled-cancel", cancelled.get("operation"));
        verifyNoInteractions(mongo);
    }

    @Test
    void pipelinesAreParseableAndMatchNodeShapes() {
        AdminNotificationService service = service(mock(MongoTemplate.class));

        assertDoesNotThrow(() -> service.adminNotificationPipeline(5, 10).forEach(Document::toJson));
        assertDoesNotThrow(() -> service.scheduledPipeline(new Document("notificationType", "scheduled"), 0, 10)
                .forEach(Document::toJson));
        assertEquals(3, service.adminNotificationPipeline(0, 10).size());
        assertEquals(4, service.scheduledPipeline(new Document(), 0, 10).size());
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
        when(find.first()).thenReturn(rows.isEmpty() ? null : rows.get(0));
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(find).into(any());
        return find;
    }

    @SuppressWarnings("unchecked")
    private AggregateIterable<Document> aggregateReturning(List<Document> rows) {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(aggregate).into(any());
        return aggregate;
    }

    private AdminNotificationService service(MongoTemplate mongo) {
        return new AdminNotificationService(mongo, new AdminMongoSupport(mongo));
    }
}
