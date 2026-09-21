package com.vedicmeet.appserver.admin;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminInfluencersServiceTest {

    @Test
    void createRequiresNameBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(Map.of("email", "a@example.com", "phone", "1", "sharePercent", "10"), null));

        assertEquals("name, email, phone, and sharePercent are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRequiresEmailBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(Map.of("name", "A", "phone", "1", "sharePercent", "10"), null));

        assertEquals("name, email, phone, and sharePercent are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRequiresPhoneBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(Map.of("name", "A", "email", "a@example.com", "sharePercent", "10"), null));

        assertEquals("name, email, phone, and sharePercent are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createRequiresSharePercentBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(Map.of("name", "A", "email", "a@example.com", "phone", "1"), null));

        assertEquals("name, email, phone, and sharePercent are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void createTreatsNumericZeroSharePercentAsMissingBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(Map.of("name", "A", "email", "a@example.com", "phone", "1", "sharePercent", 0), null));

        assertEquals("name, email, phone, and sharePercent are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void settlementPreviewRequiresPeriodStartBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.settlementPreview("507f1f77bcf86cd799439011", Map.of("periodEnd", "2026-09-09")));

        assertEquals("periodStart and periodEnd are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void settlementPreviewRequiresPeriodEndBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.settlementPreview("507f1f77bcf86cd799439011", Map.of("periodStart", "2026-09-01")));

        assertEquals("periodStart and periodEnd are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void settleRequiresPeriodStartBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.settle("507f1f77bcf86cd799439011", Map.of("periodEnd", "2026-09-09"), null));

        assertEquals("periodStart and periodEnd are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void settleRequiresPeriodEndBeforeAnyMongoCall() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminInfluencersService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.settle("507f1f77bcf86cd799439011", Map.of("periodStart", "2026-09-01"), null));

        assertEquals("periodStart and periodEnd are required.", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void metricsPipelineStagesAreValidParsedDocuments() {
        List<Document> pipeline = assertDoesNotThrow(() -> AdminInfluencersService.metricsPipeline(
                new ObjectId("507f1f77bcf86cd799439011"), Map.of("from", "2026-09-01", "to", "2026-09-09")));

        assertEquals(3, pipeline.size());
        assertEquals("507f1f77bcf86cd799439011", pipeline.get(0).get("$match", Document.class).get("influencerId").toString());
        assertTrue(pipeline.get(1).toJson().contains("totalRechargeAmount"));
        assertTrue(pipeline.get(2).toJson().contains("netEarnings"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ledgerPipelineStagesAreValidParsedDocuments() {
        List<Document> pipeline = assertDoesNotThrow(() -> AdminInfluencersService.ledgerPipeline(
                new ObjectId("507f1f77bcf86cd799439011"), Map.of("type", "EARNING", "status", "pending"), 2, 25));

        assertEquals(4, pipeline.size());
        assertEquals("users", pipeline.get(1).get("$lookup", Document.class).getString("from"));
        Document facet = pipeline.get(3).get("$facet", Document.class);
        List<Document> entries = (List<Document>) facet.get("entries");
        assertEquals(50, entries.get(1).get("$skip"));
        assertEquals(25, entries.get(2).get("$limit"));
    }

    @Test
    void settlementPreviewPipelineStagesAreValidParsedDocuments() {
        List<Document> pipeline = assertDoesNotThrow(() -> AdminInfluencersService.settlementPreviewPipeline(
                new ObjectId("507f1f77bcf86cd799439011"), "2026-09-01", "2026-09-09"));

        assertEquals(3, pipeline.size());
        assertEquals("pending", pipeline.get(0).get("$match", Document.class).getString("status"));
        assertTrue(pipeline.get(1).toJson().contains("totalRefunds"));
        assertTrue(pipeline.get(2).toJson().contains("netPayout"));
    }

    private AdminInfluencersService service(MongoTemplate mongo) {
        return new AdminInfluencersService(mongo, new AdminMongoSupport(mongo));
    }
}
