package com.vedicmeet.appserver.session;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FixedSessionBookingFactoryTest {

    private final FixedSessionBookingFactory factory = new FixedSessionBookingFactory();

    @Test
    void mapsNodeShapeRoundsTimeUpAndKeepsLatestThreeTokens() {
        Document user = user();
        Map<String, Object> body = Map.of(
                "preferredDateAt", "2026-09-08T00:00:00+05:30",
                "preferredTimeAt", "2026-09-08T10:03:20+05:30",
                "selectedLanguage", "Hindi",
                "problemDescription", "Career",
                "device_id", "device-1",
                "fcmToken", "token-4",
                "category", Map.of("_id", new ObjectId(), "priceOffer", 80,
                        "price", 100, "consultationTimeInMinutes", 15),
                "kundaliList", List.of(Map.of("name", "Shivam Tiwari", "gender", "male",
                        "placeLatLong", Map.of("lat", 28.6, "long", 77.2))));

        FixedSessionBookingFactory.Prepared result = factory.prepare(user, body);

        assertEquals(80, result.price());
        assertEquals(List.of("token-2", "token-3", "token-4"), result.fcmTokens());
        Document fixed = result.fixedSession();
        assertEquals("2026-09-08", fixed.getString("preferredDateAt"));
        assertEquals("10:05", fixed.getString("preferredTimeAt"));
        Document waitlist = fixed.get("waitlistCopy", Document.class);
        Document meta = waitlist.get("session_info", Document.class).get("sessionMeta", Document.class);
        assertEquals("10:20", meta.getString("timeTo"));
        assertEquals("80.00", meta.getString("discountPercentage"));
        assertEquals("Shivam", waitlist.get("request_form", Document.class).getString("firstName"));
        assertEquals("Tiwari", waitlist.get("request_form", Document.class).getString("lastName"));
        assertEquals("28.6", waitlist.get("request_form", Document.class)
                .get("placeLatLong", Document.class).getString("lat"));
    }

    @Test
    void claimIdIsDeterministicForClientRetries() {
        Map<String, Object> body = Map.of("preferredDateAt", "2026-09-08",
                "preferredTimeAt", "10:00", "category",
                Map.of("_id", "CAT", "priceOffer", 100, "price", 100,
                        "consultationTimeInMinutes", 30));
        assertEquals(factory.prepare(user(), body).claimId(), factory.prepare(user(), body).claimId());
    }

    @Test
    void rejectsUnpricedOrZeroLengthCategoryBeforeAnyMoneyWrite() {
        Map<String, Object> body = Map.of("category", Map.of("priceOffer", 0,
                "price", 100, "consultationTimeInMinutes", 15));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> factory.prepare(user(), body)).getMessage().contains("priceOffer"));
    }

    private Document user() {
        return new Document("_id", new ObjectId("64b000000000000000000001"))
                .append("device", new Document("fcmToken", List.of("token-1", "token-2", "token-3")));
    }
}
