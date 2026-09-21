package com.vedicmeet.appserver.admin;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminUserInsightsServiceTest {

    @Test
    void oneConsultationPreservesMissingNodeMethodBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminUserInsightsService service = service(mongo);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.getUsersWithOneCompletedConsultation(Map.of()));

        assertEquals("UserInsightsService.getUsersWithOneCompletedConsultation is not a function", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void dateWiseWaitlistPipelineParsesWithExpectedStageCount() {
        AdminUserInsightsService service = service(mock(MongoTemplate.class));

        List<Document> pipeline = service.buildDateWiseWaitlistPipeline(new Document("status", "completed"));

        assertEquals(10, pipeline.size());
        assertTrue(pipeline.get(0).containsKey("$lookup"));
        assertEquals(new Document("status", "completed"), pipeline.get(4).get("$match"));
        assertTrue(pipeline.get(5).containsKey("$group"));
        assertTrue(pipeline.get(6).containsKey("$addFields"));
        assertTrue(pipeline.get(7).containsKey("$addFields"));
        assertTrue(pipeline.get(8).containsKey("$project"));
        assertTrue(pipeline.get(9).containsKey("$sort"));
    }

    @Test
    void supportingPipelinesParseWithExpectedStageCounts() {
        AdminUserInsightsService service = service(mock(MongoTemplate.class));

        assertEquals(4, service.buildRechargePipeline(new Document()).size());
        assertEquals(2, service.buildOverallWaitlistStatsPipeline(new Document()).size());
        assertEquals(2, service.buildSpendingStatsPipeline(new Document()).size());
    }

    @Test
    void last90DaysQuirkDropsDateFilterBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminUserInsightsService service = service(mongo);

        Document filter = service.buildDateFilter(null, null, "last90days");

        assertTrue(filter.isEmpty());
        verifyNoInteractions(mongo);
    }

    @Test
    void timeZoneFilterKeepsNodeHourShape() {
        AdminUserInsightsService service = service(mock(MongoTemplate.class));

        Document filter = service.getTimeZoneFilter("12pm-2pm");

        assertFalse(filter.isEmpty());
        assertEquals(new Document("$gte", 12).append("$lt", 14), filter.get("$hour"));
    }

    private AdminUserInsightsService service(MongoTemplate mongo) {
        return new AdminUserInsightsService(mongo, mock(AdminMongoSupport.class));
    }
}
