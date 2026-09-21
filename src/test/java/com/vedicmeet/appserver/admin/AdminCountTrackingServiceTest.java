package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCountTrackingServiceTest {

    private static final String ADMIN_ID = "507f1f77bcf86cd799439011";
    private static final String ENTRY_ID = "507f1f77bcf86cd799439012";

    @Test
    void unreadCountsUsesAggregationAndPreservesAllResponseKeys() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = aggregateReturning(mongo, Collections.ENTRY_TRACKINGS,
                List.of(new Document("_id", "support_management").append("count", 4)));
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).unreadCounts(ADMIN_ID);

        verify(coll).aggregate(pipeline.capture());
        Document match = (Document) ((Document) pipeline.getValue().get(0)).get("$match");
        assertEquals(new ObjectId(ADMIN_ID), match.get("adminId"));
        assertEquals(false, match.get("readStatus.isRead"));
        assertTrue(((Document) pipeline.getValue().get(1)).toJson().contains("\"_id\": \"$type\""));
        assertEquals(List.of("cancelled_consultation", "waitlist_management", "missed_call", "progress_call",
                "completed_call", "support_management", "booked_session"), new ArrayList<>(out.keySet()));
        assertEquals(4, out.get("support_management"));
        assertEquals(0, out.get("missed_call"));
    }

    @Test
    void entriesBuildsFilterPipelineAndPaginationShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = aggregateReturning(mongo, Collections.ENTRY_TRACKINGS,
                List.of(new Document("_id", new ObjectId()).append("type", "support_management")
                        .append("readStatus", new Document("isRead", false))));
        when(coll.countDocuments(any(Bson.class))).thenReturn(21L);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).entries(ADMIN_ID, "support_management",
                Map.of("page", "2", "limit", "10", "status", "new", "priority", "high",
                        "isRead", "false", "sortBy", "priority", "sortOrder", "1"));

        verify(coll).aggregate(pipeline.capture());
        Document match = (Document) ((Document) pipeline.getValue().get(0)).get("$match");
        assertEquals("support_management", match.get("type"));
        assertEquals("new", match.get("status"));
        assertEquals("high", match.get("priority"));
        assertEquals(false, match.get("readStatus.isRead"));
        assertEquals(new Document("priority", 1), ((Document) pipeline.getValue().get(3)).get("$sort"));
        assertEquals(List.of("entries", "pagination"), new ArrayList<>(out.keySet()));
        Map<?, ?> pagination = (Map<?, ?>) out.get("pagination");
        assertEquals(2, pagination.get("page"));
        assertEquals(10, pagination.get("limit"));
        assertEquals(21L, pagination.get("total"));
        assertEquals(3L, pagination.get("pages"));
    }

    @Test
    void trackEntryPersistsWhitelistedDefaultsCountsAndVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.ENTRY_TRACKINGS);
        when(coll.countDocuments(any(Bson.class))).thenReturn(2L);
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).trackEntry(ADMIN_ID, Map.of(
                "type", "support_management",
                "entryId", "support-1",
                "reference", ENTRY_ID,
                "metadata", Map.of("title", "Need help"),
                "ignored", "drop-me"));

        verify(coll).insertOne(saved.capture());
        Document doc = saved.getValue();
        assertFalse(doc.containsKey("ignored"));
        assertEquals("admin_action", doc.get("source"));
        assertEquals(false, ((Document) doc.get("readStatus")).get("isRead"));
        assertEquals(2L, ((Document) doc.get("countChange")).get("previousCount"));
        assertEquals(3L, ((Document) doc.get("countChange")).get("currentCount"));
        assertNotNull(out.get("id"));
        assertEquals(true, out.get("isUnread"));
    }

    @Test
    void markReadBuildsStaticModelUpdateShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.ENTRY_TRACKINGS);
        UpdateResult update = mock(UpdateResult.class);
        when(update.getModifiedCount()).thenReturn(2L);
        when(coll.updateMany(any(Bson.class), any(Bson.class))).thenReturn(update);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> change = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).markRead(ADMIN_ID,
                Map.of("entryIds", List.of(ENTRY_ID), "type", "support_management"));

        verify(coll).updateMany(filter.capture(), change.capture());
        Document query = (Document) filter.getValue();
        assertEquals(new ObjectId(ADMIN_ID), query.get("adminId"));
        assertEquals(false, query.get("readStatus.isRead"));
        assertEquals("support_management", query.get("type"));
        Document set = (Document) ((Document) change.getValue()).get("$set");
        assertEquals(true, set.get("readStatus.isRead"));
        assertEquals(new ObjectId(ADMIN_ID), set.get("readStatus.readBy"));
        assertEquals(2L, out.get("modifiedCount"));
    }

    @Test
    void trackSupportReturnsRoutePayloadAndSwallowsTrackingHelperFailures() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> support = collection(mongo, Collections.CUSTOMER_SUPPORT_QUERIES);
        FindIterable<Document> find = mock(FindIterable.class);
        ObjectId supportId = new ObjectId(ENTRY_ID);
        when(support.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(new Document("_id", supportId).append("title", "Ticket")
                .append("status", "escalated").append("supportType", "TECHNICAL"));
        MongoCollection<Document> tracking = collection(mongo, Collections.ENTRY_TRACKINGS);
        when(tracking.countDocuments(any(Bson.class))).thenThrow(new RuntimeException("count down"));

        Map<String, Object> out = service(mongo).trackSupport(ADMIN_ID, ENTRY_ID);

        verify(tracking).insertOne(any(Document.class));
        assertEquals(supportId, out.get("supportId"));
        assertEquals("Ticket", out.get("title"));
        assertEquals("escalated", out.get("status"));
    }

    @Test
    void updateStatusThrowsExactMissingEntryError() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.ENTRY_TRACKINGS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).updateStatus(ADMIN_ID, ENTRY_ID, Map.of("status", "resolved")));

        assertEquals("Entry not found", error.getMessage());
    }

    @Test
    void statsUsesExactCountAndGroupShapes() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = aggregateReturning(mongo, Collections.ENTRY_TRACKINGS, List.of());
        when(coll.countDocuments(any(Bson.class))).thenReturn(7L, 3L);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).stats(ADMIN_ID,
                Map.of("startDate", "2026-09-01", "endDate", "2026-09-10"));

        verify(coll, times(3)).aggregate(pipeline.capture());
        List lastPipeline = pipeline.getAllValues().get(2);
        assertTrue(((Document) lastPipeline.get(0)).get("$match") instanceof Document);
        assertEquals(new Document("_id", "$priority").append("count", new Document("$sum", 1)),
                ((Document) lastPipeline.get(1)).get("$group"));
        assertEquals(List.of("totalEntries", "unreadEntries", "readEntries", "entriesByType",
                "entriesByStatus", "entriesByPriority"), new ArrayList<>(out.keySet()));
        assertEquals(7L, out.get("totalEntries"));
        assertEquals(3L, out.get("unreadEntries"));
        assertEquals(4L, out.get("readEntries"));
    }

    @Test
    void detailPipelinesAreParseableAndPreserveNonSchemaPopulateBug() {
        AdminCountTrackingService service = service(mock(MongoTemplate.class));

        List<Document> entryPipeline = service.entryPipeline(ADMIN_ID, ENTRY_ID);
        List<Document> entriesPipeline = service.entriesPipeline(new Document("type", "support_management"),
                5, 5, "createdAt", -1);

        assertDoesNotThrow(() -> entryPipeline.forEach(Document::toJson));
        assertDoesNotThrow(() -> entriesPipeline.forEach(Document::toJson));
        assertTrue(entryPipeline.toString().contains("readStatus.lastViewedBy"));
        assertTrue(entryPipeline.toString().contains(Collections.ADMINS));
        assertEquals(6, entriesPipeline.size());
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> aggregateReturning(MongoTemplate mongo, String name, List<Document> rows) {
        MongoCollection<Document> coll = collection(mongo, name);
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(aggregate).into(any());
        return coll;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    private AdminCountTrackingService service(MongoTemplate mongo) {
        return new AdminCountTrackingService(mongo, new AdminMongoSupport(mongo));
    }
}
