package com.vedicmeet.appserver.admin;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminConsultantInsightsServiceTest {

    @Test
    void insightsRejectsPageBelowMinimumBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsights(Map.of("page", "0")));

        assertEquals("\"page\" must be greater than or equal to 1", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void insightsRejectsPageSizeAboveMaximumBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsights(Map.of("pageSize", "101")));

        assertEquals("\"pageSize\" must be less than or equal to 100", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void insightsRejectsInvalidSortFieldBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsights(Map.of("sortField", "createdAt")));

        assertEquals("\"sortField\" must be one of [consultantName, totalAttemptsForConsultation, totalPaidChatCallSpending, completionRate, paidConsultationConversionRate, consultantType]", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void insightsRejectsInvalidSortDirectionBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsights(Map.of("sortDirection", "up")));

        assertEquals("\"sortDirection\" must be one of [asc, desc]", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void insightsRejectsInvalidTimeRangeBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsights(Map.of("timeRange", "yesterday")));

        assertEquals("\"timeRange\" must be one of [today, last7days, last30days, last60days, last90days, custom]", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void insightsRejectsInvalidConsultantGenderBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsights(Map.of("consultantGender", "other")));

        assertEquals("\"consultantGender\" must be one of [male, female, transgender, ]", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void summaryRejectsEndDateBeforeStartDateBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminConsultantInsightsService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantInsightsSummary(Map.of("startDate", "2024-01-02", "endDate", "2024-01-01")));

        assertEquals("\"endDate\" must be greater than or equal to \"ref:startDate\"", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void consultantWisePipelineStagesAreValidParsedDocuments() {
        Document match = new Document("consultant.details.gender", "female");
        List<Document> pipeline = assertDoesNotThrow(() -> AdminConsultantInsightsService.consultantWisePipeline(match));

        assertEquals(10, pipeline.size());
        assertEquals(new Document("createdAt", -1), pipeline.get(0).get("$sort"));
        assertEquals(match, pipeline.get(5).get("$match"));
        assertEquals(true, pipeline.get(9).containsKey("$project"));
    }

    @Test
    void summaryPipelinesStagesAreValidParsedDocuments() {
        Document dateFilter = new Document("createdAt", new Document("$gte", "start").append("$lte", "end"));

        List<Document> waitlist = assertDoesNotThrow(() -> AdminConsultantInsightsService.waitlistStatsPipeline(dateFilter));
        List<Document> transactions = assertDoesNotThrow(() -> AdminConsultantInsightsService.transactionStatsPipeline(dateFilter));

        assertEquals(2, waitlist.size());
        assertEquals(2, transactions.size());
        assertEquals(dateFilter, waitlist.get(0).get("$match"));
        assertEquals(dateFilter, transactions.get(0).get("$match"));
    }

    private AdminConsultantInsightsService service(MongoTemplate mongo) {
        return new AdminConsultantInsightsService(mongo, new AdminMongoSupport(mongo));
    }
}
