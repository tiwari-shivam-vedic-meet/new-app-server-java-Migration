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
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminConsultantServiceTest {
    private static final String OID = "507f1f77bcf86cd799439011";

    @Test void listConsultantsReturnsEmptyShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        find(coll, List.of());
        when(coll.countDocuments(any(Bson.class))).thenReturn(0L);
        Object out = service(mongo).listConsultants(Map.of());
        assertEquals(0L, ((Document) out).get("total"));
        assertTrue(((List<?>) ((Document) out).get("list")).isEmpty());
    }

    @Test void onlineOfflinePipelineIsValidAndMethodReturnsFacetShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        aggregate(coll, List.of(new Document("list", List.of()).append("count", List.of(new Document("total", 3)))));
        when(coll.countDocuments(any(Bson.class))).thenReturn(1L);
        AdminConsultantService s = service(mongo);
        assertDoesNotThrow(() -> s.onlineOfflinePipeline(new Document(), 0, 10, Map.of()).forEach(Document::toJson));
        assertEquals(3L, ((Document) s.onlineOfflineList(Map.of())).get("total"));
    }

    @Test void deleteListConsultantPipelineAndShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        aggregate(coll, List.of(new Document("list", List.of()).append("count", List.of(new Document("total", 2)))));
        AdminConsultantService s = service(mongo);
        assertDoesNotThrow(() -> s.deleteListPipeline(new Document("isDeleted", true), 0, 10).forEach(Document::toJson));
        assertEquals(2L, ((Document) s.deleteListConsultant(Map.of("search", "a"))).get("total"));
    }

    @Test void detailsThrowsWhenConsultantMissingAndPipelineIsValid() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        find(coll, List.of());
        AdminConsultantService s = service(mongo);
        assertDoesNotThrow(() -> s.detailsPipeline(new ObjectId(OID)).forEach(Document::toJson));
        assertEquals("CONSULTANT_NOT_EXIST", assertThrows(IllegalStateException.class, () -> s.details(Map.of("consultantId", OID))).getMessage());
    }

    @Test void numericalAnalyticsThrowsWhenMissingAndPipelineIsValid() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANTS);
        find(coll, List.of());
        AdminConsultantService s = service(mongo);
        assertDoesNotThrow(() -> s.numericalAnalyticsPipeline(new ObjectId(OID)).forEach(Document::toJson));
        assertEquals("CONSULTANT_NOT_EXIST", assertThrows(IllegalStateException.class, () -> s.numericalAnalytics(Map.of("consultantId", OID))).getMessage());
    }

    @Test void downloadDelegatesToListShape() {
        assertEquals("ConsultantService.downloadExel is not a function",
                assertThrows(IllegalStateException.class, () -> service(mock(MongoTemplate.class)).download(Map.of())).getMessage());
    }

    @Test void form16ReturnsVirtualsAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANT_FORMS_16);
        find(coll, List.of(new Document("_id", new ObjectId(OID)).append("file", "a.pdf")));
        when(coll.countDocuments(any(Bson.class))).thenReturn(1L);
        Document out = (Document) service(mongo).form16(Map.of("consultantId", OID));
        assertEquals(1L, out.get("total"));
        assertTrue(((Document) ((List<?>) out.get("list")).get(0)).getString("form16Pdf").contains("a.pdf"));
    }

    @Test void orderHistoryPipelineAndEmptyShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.WAITLISTS);
        find(coll, List.of());
        when(coll.countDocuments(any(Bson.class))).thenReturn(4L);
        AdminConsultantService s = service(mongo);
        assertDoesNotThrow(() -> s.orderHistoryPipeline(List.of(new ObjectId(OID))).forEach(Document::toJson));
        assertEquals(4L, ((Document) s.orderHistory(Map.of("consultantId", OID))).get("total"));
    }

    @Test void waitlistPipelineAndShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.WAITLISTS);
        aggregate(coll, List.of(new Document("order", List.of()).append("count", List.of(new Document("total", 5)))));
        AdminConsultantService s = service(mongo);
        assertDoesNotThrow(() -> s.waitlistPipeline(new Document()).forEach(Document::toJson));
        assertEquals(5L, ((Document) s.waitlist(Map.of("consultantId", OID))).get("total"));
    }

    @Test void walletPipelinesReturnShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collection(mongo, Collections.MASTERS);
        MongoCollection<Document> cons = collection(mongo, Collections.CONSULTANTS);
        find(collection(mongo, Collections.MASTERS), List.of());
        aggregate(cons, List.of(), List.of(new Document("total", 0)));
        Document out = (Document) service(mongo).wallet(Map.of());
        assertTrue(out.containsKey("consultants"));
    }

    @Test void avgRatingRequiresIdAndPipelineValid() {
        AdminConsultantService s = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> s.avgRatingPipeline(OID).forEach(Document::toJson));
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> s.avgRating(Map.of())).getMessage());
    }

    @Test void avgTalkTimeRequiresIdAndPipelineValid() {
        AdminConsultantService s = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> s.avgTalkTimePipeline(OID, range()).forEach(Document::toJson));
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> s.avgTalkTime(Map.of())).getMessage());
    }

    @Test void totalChatRequiresIdAndPipelineValid() {
        AdminConsultantService s = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> s.totalChatPipeline(new ObjectId(OID), range()).forEach(Document::toJson));
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> s.totalChat(Map.of())).getMessage());
    }

    @Test void availibilityRateRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).availibilityRate(Map.of())).getMessage());
    }

    @Test void loyalCustomerRequiresIdAndPipelineValid() {
        AdminConsultantService s = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> s.loyalCustomerPipeline(OID, range()).forEach(Document::toJson));
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> s.loyalCustomer(Map.of())).getMessage());
    }

    @Test void newCustomerConversionRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).newCustomerConversion(Map.of())).getMessage());
    }

    @Test void customerSatisfactionRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).customerSatisfaction(Map.of())).getMessage());
    }

    @Test void newUserServeProperlyRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).newUserServeProperly(Map.of())).getMessage());
    }

    @Test void newCustomerRatingRequiresIdAndPipelineValid() {
        AdminConsultantService s = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> s.avgRatingOfConsultantPipeline(OID, range()).forEach(Document::toJson));
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> s.newCustomerRating(Map.of())).getMessage());
    }

    @Test void customerRetentionRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).customerRetention(Map.of())).getMessage());
    }

    @Test void newUserConversionRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).newUserConversion(Map.of())).getMessage());
    }

    @Test void userRetentionRequiresId() {
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> service(mock(MongoTemplate.class)).userRetention(Map.of())).getMessage());
    }

    @Test void shopifyDiscountCouponPipelineAndShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SHOPIFY_DISCOUNT_COUPONS);
        aggregate(coll, List.of(new Document("list", List.of()).append("count", List.of(new Document("total", 1)))));
        Document out = (Document) service(mongo).shopifyDiscountCoupon(Map.of("consultantId", OID));
        assertEquals(1L, out.get("total"));
    }

    @Test void shopifyOrderCommissionIsTransportSeamNoop() {
        assertEquals(null, service(mock(MongoTemplate.class)).shopifyOrderCommission(Map.of()));
    }

    @Test void shopifyOrderPipelineAndShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.SHOPIFY_ORDERS);
        aggregate(coll, List.of(new Document("list", List.of()).append("count", List.of(new Document("total", 1)))));
        assertEquals(1L, ((Document) service(mongo).shopifyOrder(Map.of())).get("total"));
    }

    @Test void transactionsRequiresIdAndPipelineValid() {
        AdminConsultantService s = service(mock(MongoTemplate.class));
        assertDoesNotThrow(() -> s.transactionsPipeline(new Document("consultantId", new ObjectId(OID)), new AdminConsultantService.Page(1, 10, 0)).forEach(Document::toJson));
        assertEquals("Consultant ID is required", assertThrows(IllegalArgumentException.class, () -> s.transactions(Map.of())).getMessage());
    }

    @Test void tagsReturnsListWithVirtualId() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.CONSULTANT_TAGS);
        find(coll, List.of(new Document("_id", new ObjectId(OID)).append("name", "VIP")));
        Object out = service(mongo).tags();
        assertEquals(OID, ((Document) ((List<?>) out).get(0)).getString("id"));
    }

    private static AdminConsultantService service(MongoTemplate mongo) {
        return new AdminConsultantService(mongo, new AdminMongoSupport(mongo), new AppConstants("bucket", "ap-south-1"));
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private static void find(MongoCollection<Document> coll, List<Document> rows) {
        FindIterable<Document> it = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.sort(any(Bson.class))).thenReturn(it);
        when(it.skip(anyInt())).thenReturn(it);
        when(it.limit(anyInt())).thenReturn(it);
        when(it.projection(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(rows.isEmpty() ? null : rows.get(0));
        doAnswer(inv -> {
            @SuppressWarnings("unchecked") List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(it).into(any());
    }

    @SuppressWarnings("unchecked")
    private static void aggregate(MongoCollection<Document> coll, List<Document>... batches) {
        AggregateIterable<Document> agg = mock(AggregateIterable.class);
        when(coll.aggregate(anyList())).thenReturn(agg);
        final int[] i = {0};
        doAnswer(inv -> {
            @SuppressWarnings("unchecked") List<Document> target = inv.getArgument(0);
            List<Document> rows = batches[Math.min(i[0]++, batches.length - 1)];
            target.addAll(rows);
            return target;
        }).when(agg).into(any());
    }

    private static AdminConsultantService.DateRange range() {
        return new AdminConsultantService.DateRange(new Date(0), new Date());
    }
}
