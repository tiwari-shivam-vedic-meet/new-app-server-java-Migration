package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMembershipDiscountServiceTest {

    private static final String ID = "507f1f77bcf86cd799439011";

    @Test
    void addMembershipPersistsWhitelistedFieldsDefaultsAndVirtualId() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.MEMBERSHIP_DISCOUNTS);
        findFirst(coll, null);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).add(Map.<String, Object>ofEntries(
                Map.entry("type", "membership"),
                Map.entry("title", "Gold"),
                Map.entry("planType", "GOLD"),
                Map.entry("platform", "ANDROID"),
                Map.entry("details", "Annual"),
                Map.entry("membershipPrice", "999"),
                Map.entry("membershipDiscountPrice", "799"),
                Map.entry("discountPercentage", "20"),
                Map.entry("membershipDuration", "365"),
                Map.entry("image", "discount/gold.png"),
                Map.entry("ignored", "drop")));

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertFalse(saved.containsKey("ignored"));
        assertEquals("membership", saved.get("type"));
        assertEquals(999, saved.get("membershipPrice"));
        assertEquals(799, saved.get("membershipDiscountPrice"));
        assertEquals(20, saved.get("discountPercentage"));
        assertEquals(true, saved.get("status"));
        assertNotNull(saved.get("createdAt"));
        assertNotNull(saved.get("updatedAt"));
        assertEquals(String.valueOf(saved.get("_id")), out.get("id"));
    }

    @Test
    void addMembershipRejectsDuplicatePlanTypeBeforeInsert() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.MEMBERSHIP_DISCOUNTS);
        findFirst(coll, new Document("_id", new ObjectId()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).add(Map.of("type", "membership", "planType", "GOLD", "image", "x.png")));

        assertEquals("MEMBERSHIP_PLAN_TYPE_EXIST", error.getMessage());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void addCouponRequiresImageBecauseNodeRequiredUploadedFile() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        findFirst(coll, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).add(Map.of("type", "coupon", "title", "Sale")));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void addCouponPersistsSchemaFieldsAndGeneratedCouponCodeThenReturnsNull() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        findFirst(coll, null);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).add(Map.of(
                "type", "coupon",
                "couponType", "COUPON",
                "title", "September",
                "couponDiscount", "15",
                "couponStartDate", "2026-09-10T00:00:00Z",
                "couponEndDate", "2026-09-12T00:00:00Z",
                "image", "coupon/sale.png",
                "startDate", "drop"));

        assertNull(out);
        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertFalse(saved.containsKey("type"));
        assertFalse(saved.containsKey("startDate"));
        assertEquals("COUPON", saved.get("couponType"));
        assertEquals(15, saved.get("couponDiscount"));
        assertTrue(String.valueOf(saved.get("couponCode")).matches("COU[1V2E3D4I5C]{3}\\d{2}"));
        assertEquals(true, saved.get("status"));
    }

    @Test
    void addCouponPreservesUtcDayComparisonForEndBeforeStart() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        findFirst(coll, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).add(Map.of(
                        "type", "coupon", "title", "Bad", "image", "x.png",
                        "couponStartDate", "2026-09-12T10:00:00Z",
                        "couponEndDate", "2026-09-10T10:00:00Z")));

        assertEquals("END_GREATER_START", error.getMessage());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void listCouponUsesFacetPipelineWithOfferExclusionProjectionAndTotalShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        Document facet = new Document("list", List.of(new Document("title", "Coupon")))
                .append("count", List.of(new Document("total", 4)));
        when(aggregate.into(any())).thenReturn(new ArrayList<>(List.of(facet)));
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).list(Map.of(
                "type", "coupon", "couponType", "COUPON", "search", "sep", "page", "2", "limit", "5"));

        verify(coll).aggregate(pipeline.capture());
        List<Document> stages = pipeline.getValue();
        Document match = (Document) stages.get(0).get("$match");
        assertEquals("COUPON", match.get("couponType"));
        assertEquals(new Document("$regex", ".*sep.*").append("$options", "i"), match.get("title"));
        assertTrue(String.valueOf(match.get("$expr")).contains("OFFERS"));
        Document project = (Document) stages.get(1).get("$project");
        assertTrue(project.containsKey("couponDiscount"));
        assertEquals(4L, out.get("total"));
        assertEquals(1, ((List<?>) out.get("list")).size());
    }

    @Test
    void updateRechargeAllowsNullStringExpiryAndWhitelistsSet() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.MEMBERSHIP_DISCOUNTS);
        FindIterable<Document> firstFind = findFirst(coll, new Document("_id", new ObjectId(ID)));
        FindIterable<Document> duplicateFind = mock(FindIterable.class);
        when(duplicateFind.first()).thenReturn(null);
        when(coll.find(any(Bson.class))).thenReturn(firstFind, duplicateFind);
        Document updated = new Document("_id", new ObjectId(ID)).append("type", "recharge").append("title", "R");
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(updated);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).update(Map.of(
                "type", "recharge", "membershipDiscountId", ID, "title", "R",
                "rechargeAmount", "100", "rechargeCoins", "110", "rechargeDiscount", "10",
                "rechargeOfferExpiry", "null", "ignored", "drop"));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertFalse(set.containsKey("membershipDiscountId"));
        assertFalse(set.containsKey("ignored"));
        assertEquals(null, set.get("rechargeOfferExpiry"));
        assertEquals(110, set.get("rechargeCoins"));
        assertEquals(ID, out.get("id"));
    }

    @Test
    void blockUnblockRechargeClearsExpiryWhenDisabling() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.MEMBERSHIP_DISCOUNTS);
        findFirst(coll, new Document("_id", new ObjectId(ID)).append("rechargeOfferExpiry", new java.util.Date()));
        Document updated = new Document("_id", new ObjectId(ID)).append("status", false);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(updated);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).blockUnblock(Map.of(
                "type", "recharge", "membershipDiscountId", ID, "status", false));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertEquals(false, set.get("status"));
        assertTrue(set.containsKey("rechargeOfferExpiry"));
        assertEquals(ID, out.get("id"));
    }

    @Test
    void detailsCouponChecksCouponTypeAndReturnsFirstAggregateDocument() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        findFirst(coll, new Document("_id", new ObjectId(ID)));
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        when(aggregate.into(any())).thenReturn(new ArrayList<>(List.of(new Document("title", "Coupon"))));
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).details(Map.of("type", "coupon", "couponId", ID, "couponType", "PROMOCODE"));

        verify(coll).aggregate(pipeline.capture());
        Document match = (Document) ((Document) pipeline.getValue().get(0)).get("$match");
        assertEquals("PROMOCODE", match.get("couponType"));
        assertEquals("Coupon", out.get("title"));
        assertFalse(out.containsKey("id"));
    }

    @Test
    void addMasterCouponParsesDurationAndUpsertsMasterCoupon() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.MASTERS);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("masterCoupon", new Document("couponCode", "FREE").append("totalMinutes", 5)));
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).addMasterCoupon(Map.of(
                "type", "master", "title", "FREE", "couponDuration", "5"));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document masterCoupon = (Document) ((Document) ((Document) update.getValue()).get("$set")).get("masterCoupon");
        assertEquals("FREE", masterCoupon.get("couponCode"));
        assertEquals(5, masterCoupon.get("totalMinutes"));
        assertEquals("FREE", ((Document) out.get("masterCoupon")).get("couponCode"));
    }

    @Test
    void listOffersUsesFindSortSkipLimitAndAddsVirtualIds() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.skip(10)).thenReturn(find);
        when(find.limit(10)).thenReturn(find);
        when(find.into(any())).thenReturn(new ArrayList<>(List.of(new Document("_id", new ObjectId(ID)).append("title", "Offer"))));
        when(coll.countDocuments(any(Bson.class))).thenReturn(1L);

        Map<String, Object> out = service(mongo).listOffers(Map.of("type", "OFFERS", "page", "2"));

        assertEquals(1L, out.get("total"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) out.get("list")).get(0);
        assertEquals(ID, row.get("id"));
    }

    @Test
    void editOffersThrowsWhenMissingAfterDuplicateCheck() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COUPONS);
        findFirst(coll, null);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).editOffers(Map.of("offerId", ID, "title", "Offer")));

        assertEquals("OFFER_NOT_EXIST", error.getMessage());
    }

    private AdminMembershipDiscountService service(MongoTemplate mongo) {
        return new AdminMembershipDiscountService(mongo, new AdminMongoSupport(mongo));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> findFirst(MongoCollection<Document> coll, Document first) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(first);
        return find;
    }
}
