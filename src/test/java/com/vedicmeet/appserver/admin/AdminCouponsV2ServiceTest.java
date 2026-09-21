package com.vedicmeet.appserver.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminCouponsV2ServiceTest {

    @Mock
    MongoTemplate mongo;

    @Mock
    AdminMongoSupport support;

    AdminCouponsV2Service service;

    @BeforeEach
    void setUp() {
        service = new AdminCouponsV2Service(mongo, support);
    }

    @Test
    void createRejectsMissingRequiredFieldsBeforeMongo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(Map.of("code", "SAVE10"), "admin"));

        assertEquals("code, type, discountPercent, appliesOn, validFrom, validTo are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRejectsTypeAppliesOnMismatchBeforeMongo() {
        Map<String, Object> body = Map.of(
                "code", "SAVE10",
                "type", "INFLUENCER",
                "discountPercent", 10,
                "appliesOn", "CONSULTATION",
                "validFrom", "2026-01-01",
                "validTo", "2026-12-31",
                "influencerId", "64b64c08c59f4d67dcb67501");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(body, "admin"));

        assertEquals("Coupon type INFLUENCER must have appliesOn = RECHARGE.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRejectsUnknownTypeWithUndefinedMessageBeforeMongo() {
        Map<String, Object> body = Map.of(
                "code", "SAVE10",
                "type", "SPECIAL",
                "discountPercent", 10,
                "appliesOn", "CONSULTATION",
                "validFrom", "2026-01-01",
                "validTo", "2026-12-31");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(body, "admin"));

        assertEquals("Coupon type SPECIAL must have appliesOn = undefined.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRejectsMissingInfluencerIdBeforeMongo() {
        Map<String, Object> body = Map.of(
                "code", "SAVE10",
                "type", "INFLUENCER",
                "discountPercent", 10,
                "appliesOn", "RECHARGE",
                "validFrom", "2026-01-01",
                "validTo", "2026-12-31");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(body, "admin"));

        assertEquals("INFLUENCER coupons require an influencerId", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRejectsMissingConsultantIdBeforeMongo() {
        Map<String, Object> body = Map.of(
                "code", "SAVE10",
                "type", "CONSULTANT",
                "discountPercent", 10,
                "appliesOn", "CONSULTATION",
                "validFrom", "2026-01-01",
                "validTo", "2026-12-31");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(body, "admin"));

        assertEquals("CONSULTANT coupons require a consultantId", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void validateRejectsMissingCodeOrContextBeforeMongo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validate(Map.of("code", "SAVE10")));

        assertEquals("code and context (RECHARGE|CONSULTATION) are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void validateRejectsInvalidContextBeforeMongo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validate(Map.of("code", "SAVE10", "context", "CART")));

        assertEquals("context must be RECHARGE or CONSULTATION.", error.getMessage());
        verifyNoInteractions(mongo);
    }
}
