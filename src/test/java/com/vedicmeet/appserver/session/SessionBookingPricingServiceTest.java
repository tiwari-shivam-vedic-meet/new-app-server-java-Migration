package com.vedicmeet.appserver.session;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionBookingPricingServiceTest {

    @Test
    void basePrice_requiresFiveMinutesAndCapsTimeByWallet() {
        FakeOffers offers = new FakeOffers();
        var quote = new SessionBookingPricingService(offers).quote("chat",
                user(100), consultant(10), false, null);

        assertEquals(10, quote.basePrice());
        assertEquals(50, quote.requiredBalance());
        assertEquals(600, quote.maximumTimeSeconds());
        assertFalse(quote.minimumBalanceMissing());
        assertFalse(quote.offerApplied());
    }

    @Test
    void manualCouponDiscountsPerMinuteAndBuildsNodeSnapshot() {
        FakeOffers offers = new FakeOffers();
        Document coupon = new Document("_id", new ObjectId()).append("code", "SAVE20")
                .append("discountPercent", 20);
        offers.selected = new SessionOfferStore.Selection(SessionOfferStore.Kind.MANUAL_COUPON,
                new ObjectId(), new ObjectId(), null, null, coupon, null, null);

        var quote = new SessionBookingPricingService(offers).quote("chat",
                user(100), consultant(10), true, "SAVE20");

        assertEquals(8, quote.price());
        assertEquals(40, quote.requiredBalance());
        assertEquals(720, quote.maximumTimeSeconds());
        assertEquals("MANUAL_COUPON", quote.couponSnapshot().getString("type"));
    }

    @Test
    void fixedOfferUsesOneFixedWalletCharge() {
        FakeOffers offers = new FakeOffers();
        offers.selected = systemOffer("FIXED", 49, 300, List.of("CHAT"));
        var quote = new SessionBookingPricingService(offers).quote("chat",
                user(50), consultant(10), true, null);
        assertEquals(49, quote.requiredBalance());
        assertEquals(300, quote.maximumTimeSeconds());
        assertFalse(quote.minimumBalanceMissing());
    }

    @Test
    void percentOfferUsesDiscountedPerMinutePrice() {
        FakeOffers offers = new FakeOffers();
        offers.selected = systemOffer("PERCENT", 50, 300, List.of("CALL"));
        var quote = new SessionBookingPricingService(offers).quote("audio",
                user(25), consultant(10), true, null);
        assertEquals(5, quote.price());
        assertEquals(25, quote.requiredBalance());
        assertFalse(quote.minimumBalanceMissing());
    }

    @Test
    void inapplicableOfferFallsBackWithoutConsumingIt() {
        FakeOffers offers = new FakeOffers();
        offers.selected = systemOffer("FIXED", 1, 300, List.of("CALL"));
        SessionBookingPricingService service = new SessionBookingPricingService(offers);
        var quote = service.quote("chat", user(100), consultant(10), true, null);
        service.consume(quote);
        assertFalse(quote.offerApplied());
        assertEquals(10, quote.price());
        assertNull(offers.consumed);
    }

    private SessionOfferStore.Selection systemOffer(String type, double price, long seconds,
                                                     List<String> modes) {
        ObjectId ruleId = new ObjectId();
        ObjectId variantId = new ObjectId();
        return new SessionOfferStore.Selection(SessionOfferStore.Kind.SYSTEM_OFFER,
                new ObjectId(), null, ruleId, variantId, null,
                new Document("_id", ruleId).append("applicableModes", modes)
                        .append("consultants", new Document("type", "ALL"))
                        .append("consultantStatus", "ALL"),
                new Document("_id", variantId).append("type", type).append("price", price)
                        .append("duration", seconds).append("isActive", true));
    }

    private Document user(double wallet) { return new Document("_id", new ObjectId()).append("wallet", wallet); }
    private Document consultant(double price) {
        return new Document("_id", new ObjectId()).append("price", new Document("default", price))
                .append("sessionsStatus", new Document("isChatLive", true).append("isVoiceLive", true));
    }

    private static class FakeOffers implements SessionOfferStore {
        Selection selected;
        Selection consumed;
        @Override public Selection select(Document user, Document consultant, String mode, String code) { return selected; }
        @Override public void consume(Selection selection) { consumed = selection; }
    }
}
