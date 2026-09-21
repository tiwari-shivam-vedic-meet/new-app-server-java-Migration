package com.vedicmeet.appserver.session;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserSessionCouponServiceTest {

    private MongoTemplate mongo;
    private Document actor;
    private UserSessionCouponService service;

    @BeforeEach
    void setup() {
        mongo = mock(MongoTemplate.class);
        actor = new Document("_id", new ObjectId());
        service = new UserSessionCouponService(mongo);
    }

    @Test
    void validatesV2RechargeAndCalculatesExtraCoinsWithoutDiscountingGst() {
        Document coupon = new Document("_id", new ObjectId()).append("code", "SAVE20")
                .append("isActive", true).append("type", "INFLUENCER")
                .append("appliesOn", "RECHARGE").append("discountPercent", 20)
                .append("influencerId", new ObjectId()).append("usageLimit", 2)
                .append("validFrom", new Date(System.currentTimeMillis() - 1000))
                .append("validTo", new Date(System.currentTimeMillis() + 60_000));
        collection(Collections.V2_COUPONS, coupon);
        collection(Collections.USER_COUPON_STATES, new Document("timesUsed", 0));
        collection(Collections.INFLUENCERS, new Document("_id", coupon.get("influencerId"))
                .append("isActive", true).append("sharePercent", 10));

        UserSessionCouponService.CouponResult result = service.validate(actor,
                Map.of("code", "save20", "context", "RECHARGE",
                        "rechargeBaseAmount", 100, "gstAmount", 18));

        assertEquals("v2", result.version());
        assertEquals(20.0, result.data().getDouble("extraCoins"));
        assertEquals(118.0, result.data().getDouble("finalAmountPaid"));
        assertEquals(10.0, result.data().getDouble("influencerEarning"));
    }

    @Test
    void fallsBackToCouponMasterForExistingMobileContract() {
        collection(Collections.V2_COUPONS, null);
        Document legacy = new Document("_id", new ObjectId()).append("code", "FREE5MINUTES")
                .append("isActive", true);
        collection(Collections.COUPON_MASTERS, legacy);

        UserSessionCouponService.CouponResult result = service.validate(actor,
                Map.of("coupon", "FREE5MINUTES"));
        assertEquals("legacy", result.version());
        assertEquals("Offer applied!", result.message());
        assertSame(legacy, result.data());
    }

    @Test
    void reportsLegacyCouponReuseAgainstCompletedWaitlist() {
        collection(Collections.V2_COUPONS, null);
        collection(Collections.COUPON_MASTERS, null);
        Document coupon = new Document("_id", new ObjectId()).append("couponCode", "OLD10");
        collection(Collections.COUPONS, coupon);
        collection(Collections.WAITLISTS, new Document("_id", new ObjectId()));

        assertEquals("Coupon already used!", assertThrows(IllegalStateException.class,
                () -> service.validate(actor, Map.of("code", "OLD10"))).getMessage());
    }

    @SuppressWarnings("unchecked")
    private void collection(String name, Document value) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> result = mock(FindIterable.class);
        when(result.first()).thenReturn(value);
        when(result.projection(any(Bson.class))).thenReturn(result);
        when(collection.find(any(Bson.class))).thenReturn(result);
        when(mongo.getCollection(name)).thenReturn(collection);
    }
}
