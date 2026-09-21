package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminPayoutServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void listComputesPayableCoinsAndReturnsFacetShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> consultants = collection(mongo, Collections.CONSULTANTS);
        Document row = new Document("_id", new ObjectId())
                .append("consWallet", new Document("totalCredits", 1000.0).append("totalDebits", 200.0));
        Document facet = new Document("list", List.of(row))
                .append("count", List.of(new Document("total", 1)));
        AggregateIterable<Document> agg = aggregateReturning(List.of(facet));
        when(consultants.aggregate(anyList())).thenReturn(agg);

        MongoCollection<Document> masters = collection(mongo, Collections.MASTERS);
        FindIterable<Document> master = findReturning(List.of(new Document("payment",
                new Document("PG", 2.5).append("TDS", 10))));
        when(masters.find(any(Bson.class))).thenReturn(master);

        Map<String, Object> out = service(mongo).list(Map.of("page", "1", "limit", "1000"));

        assertEquals(1, out.get("total"));
        List<?> finalList = (List<?>) out.get("finalList");
        assertEquals(1, finalList.size());
        // available = 1000 - 200 = 800; pg = 800*2.5/100 = 20; subtotal = 780; tds = 780*10/100 = 78; payable = 702.00
        assertEquals("702.00", ((Document) finalList.get(0)).get("coins"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAppliesStatusFilterAndUnescapedSearchToNameAndUserName() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> consultants = collection(mongo, Collections.CONSULTANTS);
        Document facet = new Document("list", List.of()).append("count", List.of());
        AggregateIterable<Document> agg = aggregateReturning(List.of(facet));
        when(consultants.aggregate(anyList())).thenReturn(agg);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).list(Map.of("search", "a+b"));

        verify(consultants).aggregate(pipeline.capture());
        Document match = (Document) ((Document) pipeline.getValue().get(0)).get("$match");
        assertEquals(Boolean.TRUE, match.get("status"));
        assertEquals(Boolean.FALSE, match.get("isDeleted"));
        assertEquals(new Document("$regex", ".*a+b.*").append("$options", "i"), match.get("name"));
        assertEquals(new Document("$regex", ".*a+b.*").append("$options", "i"), match.get("userName"));
        assertEquals(0, out.get("total"));
        // The last stage is the $facet with list/count sub-pipelines.
        assertTrue(((Document) pipeline.getValue().get(pipeline.getValue().size() - 1)).containsKey("$facet"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForSpecificMonthReturnsTrackWithMediaPrefixedSheet() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> snapshots = collection(mongo, Collections.CONSULTANT_PAYOUT_MONTH_ENDS);
        AggregateIterable<Document> agg = aggregateReturning(List.of(new Document("_id", new ObjectId()).append("name", "A")));
        when(snapshots.aggregate(anyList())).thenReturn(agg);
        MongoCollection<Document> tracks = collection(mongo, Collections.CONSULTANT_PAYOUT_TRACKS);
        FindIterable<Document> trackIter = findReturning(List.of(new Document("month", "1").append("year", "2024")
                        .append("payoutSheet", "sheet.xlsx")));
        when(tracks.find(any(Bson.class))).thenReturn(trackIter);

        Map<String, Object> out = service(mongo).specificMonth(Map.of("month", "1", "year", "2024"));

        assertEquals(1, out.get("total"));
        assertEquals(1, ((List<?>) out.get("finalList")).size());
        Document track = (Document) out.get("prevPayoutTrack");
        assertEquals("https://vedic-meet-bucket.s3.ap-south-1.amazonaws.com/sheet.xlsx", track.get("payoutSheet"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForSpecificMonthReturnsNullTrackWhenMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> snapshots = collection(mongo, Collections.CONSULTANT_PAYOUT_MONTH_ENDS);
        AggregateIterable<Document> agg = aggregateReturning(List.of());
        when(snapshots.aggregate(anyList())).thenReturn(agg);
        MongoCollection<Document> tracks = collection(mongo, Collections.CONSULTANT_PAYOUT_TRACKS);
        FindIterable<Document> track = findReturning(List.of());
        when(tracks.find(any(Bson.class))).thenReturn(track);

        Map<String, Object> out = service(mongo).specificMonth(Map.of("month", "1", "year", "2024"));

        assertEquals(0, out.get("total"));
        assertNull(out.get("prevPayoutTrack"));
    }

    @Test
    void uploadWithSettlementIsGatedWhenExecutionDisabled() {
        MongoTemplate mongo = mock(MongoTemplate.class);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).uploadWithSettlement(Map.of("any", "body")));

        assertEquals("Payout settlement execution is disabled pending human review", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void uploadWithSettlementNeverMovesMoneyEvenWhenExecutionEnabled() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminPayoutService enabled = new AdminPayoutService(mongo, new AdminMongoSupport(mongo),
                new AppConstants("vedic-meet-bucket", "ap-south-1"), true);

        assertThrows(UnsupportedOperationException.class, () -> enabled.uploadWithSettlement(Map.of()));
        verifyNoInteractions(mongo);
    }

    @Test
    void excelExportsAreDocumentedSeamsWithNoDbReads() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminPayoutService service = service(mongo);

        assertEquals(Map.of(), service.export(Map.of()));
        assertEquals(Map.of(), service.exportWithPeriod(Map.of()));
        verifyNoInteractions(mongo);
    }

    @Test
    void listPipelineStagesAreParseable() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> consultants = collection(mongo, Collections.CONSULTANTS);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);
        Document facet = new Document("list", List.of()).append("count", List.of());
        AggregateIterable<Document> agg = aggregateReturning(List.of(facet));
        when(consultants.aggregate(anyList())).thenReturn(agg);

        service(mongo).list(Map.of());

        verify(consultants).aggregate(pipeline.capture());
        assertDoesNotThrow(() -> pipeline.getValue().forEach(stage -> ((Document) stage).toJson()));
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

    private AdminPayoutService service(MongoTemplate mongo) {
        return new AdminPayoutService(mongo, new AdminMongoSupport(mongo),
                new AppConstants("vedic-meet-bucket", "ap-south-1"), false);
    }
}
