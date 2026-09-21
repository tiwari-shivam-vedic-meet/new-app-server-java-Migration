package com.vedicmeet.appserver.admin;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminSessionBreakdownServiceTest {

    @Mock
    MongoTemplate mongo;

    @Mock
    AdminMongoSupport support;

    AdminSessionBreakdownService service;

    @BeforeEach
    void setUp() {
        service = new AdminSessionBreakdownService(mongo, support);
    }

    // ── Validation guards (faithful Node messages, thrown before Mongo) ──

    @Test
    void breakdownRejectsInvalidObjectIdBeforeMongo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.breakdown("not-a-valid-id"));

        assertEquals("Invalid session ID", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void consultantCouponRequiresConsultantIdBeforeMongo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.consultantCouponAnalytics(new HashMap<>()));

        assertEquals("consultantId is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void consultantCouponRejectsMalformedConsultantIdBeforeMongo() {
        Map<String, String> query = new HashMap<>();
        query.put("consultantId", "abc");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.consultantCouponAnalytics(query));

        assertEquals("consultantId is required", error.getMessage());
        verifyNoInteractions(mongo);
    }

    // ── Aggregation pipelines are structurally valid Mongo JSON ──────────

    @Test
    void aggregationPipelinesAreValidJson() {
        Document match = new Document("status", "completed");

        List<Document> summary = service.summaryPipeline(match);
        assertEquals(2, summary.size());
        assertTrue(summary.get(0).containsKey("$match"));
        assertTrue(((Document) summary.get(1).get("$group")).containsKey("sessionsWithCoupon"));

        List<Document> perCoupon = service.perCouponPipeline(match, 0, 10);
        assertEquals(5, perCoupon.size());
        assertEquals(0, perCoupon.get(3).getInteger("$skip"));
        assertEquals(10, perCoupon.get(4).getInteger("$limit"));

        List<Document> overall = service.overallPipeline(match);
        assertEquals(2, overall.size());
        assertTrue(((Document) overall.get(1).get("$group")).containsKey("avgDurMins"));

        List<Document> couponBreakdown = service.couponBreakdownPipeline(match);
        assertEquals(4, couponBreakdown.size());
        assertEquals(20, couponBreakdown.get(3).getInteger("$limit"));

        for (List<Document> pipeline : List.of(summary, perCoupon, overall, couponBreakdown)) {
            for (Document stage : pipeline) {
                // round-trips through the strict BSON JSON parser => valid pipeline JSON
                assertNotNull(Document.parse(stage.toJson()));
            }
        }
    }

    // ── buildBreakdown pure numeric logic ───────────────────────────────

    @Test
    void buildBreakdownComputesDurationsAndAmountsWithoutCoupon() {
        Document session = new Document("_id", "s1")
                .append("status", "completed")
                .append("session_info", new Document("price", 20).append("mode", "audio"))
                .append("onCompletion", new Document("callDurationInSeconds", 120)
                        .append("extraDuration", 0)
                        .append("baseAmount", 100)
                        .append("platformAmount", 40)
                        .append("consultantAmount", 60))
                .append("timeLap", new Document());

        Map<String, Object> breakdown = service.buildBreakdown(session, 40);

        assertEquals("completed", breakdown.get("status"));
        assertEquals("audio", breakdown.get("callType"));
        assertNull(breakdown.get("coupon"));

        Map<?, ?> pricing = (Map<?, ?>) breakdown.get("pricing");
        assertEquals(20.0, ((Number) pricing.get("baseRate")).doubleValue());
        assertNull(pricing.get("offerRate"));
        assertFalse((Boolean) pricing.get("hasOffer"));

        Map<?, ?> total = (Map<?, ?>) ((Map<?, ?>) breakdown.get("duration")).get("total");
        assertEquals(120.0, ((Number) total.get("seconds")).doubleValue());
        assertEquals(2.0, ((Number) total.get("minutes")).doubleValue());

        Map<?, ?> amounts = (Map<?, ?>) breakdown.get("amounts");
        assertEquals(100.0, ((Number) amounts.get("totalAmount")).doubleValue());
        assertEquals(40.0, ((Number) amounts.get("platformFeeRate")).doubleValue()); // (40/100)*100
        assertEquals(40.0, ((Number) amounts.get("offerAmount")).doubleValue());      // baseRate * baseMinutes = 20 * 2
    }

    @Test
    void buildBreakdownAppliesCouponOfferPricing() {
        Document session = new Document("_id", "s2")
                .append("status", "completed")
                .append("session_info", new Document("price", 20).append("mode", "video"))
                .append("coupon", new Document("couponCode", "SAVE50")
                        .append("type", "OFFERS")
                        .append("couponDiscount", 50)
                        .append("title", "Half off"))
                .append("onCompletion", new Document("callDurationInSeconds", 120)
                        .append("extraDuration", 0)
                        .append("baseAmount", 100)
                        .append("platformAmount", 40)
                        .append("consultantAmount", 60))
                .append("timeLap", new Document());

        Map<String, Object> breakdown = service.buildBreakdown(session, 40);

        Map<?, ?> pricing = (Map<?, ?>) breakdown.get("pricing");
        assertEquals(10.0, ((Number) pricing.get("offerRate")).doubleValue()); // 20 - (20 * 50 / 100)
        assertTrue((Boolean) pricing.get("hasOffer"));

        Map<?, ?> coupon = (Map<?, ?>) breakdown.get("coupon");
        assertEquals("SAVE50", coupon.get("code"));
        assertEquals("OFFERS", coupon.get("type"));
        assertEquals("CONSULTATION", coupon.get("appliedOn")); // no appliesOn, type != INFLUENCER
    }
}
