package com.vedicmeet.appserver.admin;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AdminEnhancedDashboardServiceTest {

    @Test
    void revenueAnalyticsPipelinesMatchNodeShape() {
        AdminEnhancedDashboardService service = service();
        Date start = date("2024-01-01T00:00:00Z");
        Date end = date("2024-01-31T23:59:59.999Z");

        List<Document> byService = service.revenueByServicePipeline(start, end);
        List<Document> daily = service.dailyRevenuePipeline(start, end);
        List<Document> monthly = service.monthlyRevenuePipeline(start, end);
        List<Document> period = service.revenueForPeriodPipeline(start, end);
        List<Document> today = service.todayRevenuePipeline(start);

        assertEquals(2, byService.size());
        assertTrue(byService.get(0).containsKey("$match"));
        assertTrue(byService.get(1).toJson().contains("totalAmountPayToPlateform"));
        assertEquals(3, daily.size());
        assertTrue(daily.get(1).toJson().contains("%Y-%m-%d"));
        assertEquals(3, monthly.size());
        assertTrue(monthly.get(1).toJson().contains("$week"));
        assertEquals(2, period.size());
        assertEquals(2, today.size());
    }

    @Test
    void userAnalyticsPipelinesMatchNodeShape() {
        AdminEnhancedDashboardService service = service();
        Date start = date("2024-01-01T00:00:00Z");
        Date end = date("2024-01-31T23:59:59.999Z");

        List<Document> growth = service.userGrowthPipeline(start, end);
        List<Document> trends = service.userGrowthTrendsPipeline("%Y-%U");
        List<Document> satisfaction = service.userSatisfactionPipeline();

        assertEquals(3, growth.size());
        assertTrue(growth.get(1).toJson().contains("newUsers"));
        assertEquals(2, trends.size());
        assertTrue(trends.get(0).toJson().contains("%Y-%U"));
        assertEquals(2, satisfaction.size());
        assertTrue(satisfaction.get(1).toJson().contains("avgRating"));
    }

    @Test
    void consultantAndSessionPipelinesMatchNodeShape() {
        AdminEnhancedDashboardService service = service();
        Date start = date("2024-01-01T00:00:00Z");
        Date end = date("2024-01-31T23:59:59.999Z");

        List<Document> top = service.topPerformersPipeline(start, end);
        List<Document> ratings = service.ratingDistributionPipeline(start, end);
        List<Document> sessionTypes = service.sessionTypesPipeline(start, end);
        List<Document> distribution = service.serviceDistributionPipeline(start, end);
        List<Document> earnings = service.consultantEarningsPipeline(start, end);
        List<Document> sessions = service.sessionAnalyticsPipeline(start, end);
        List<Document> support = service.supportAnalyticsPipeline(start, end);

        assertEquals(7, top.size());
        assertTrue(top.get(2).toJson().contains("consultants"));
        assertTrue(top.get(4).toJson().contains("consultantName"));
        assertEquals(3, ratings.size());
        assertTrue(ratings.get(1).toJson().contains("$round"));
        assertEquals(2, sessionTypes.size());
        assertEquals(2, distribution.size());
        assertTrue(distribution.get(1).toJson().contains("session_info.coins"));
        assertEquals(3, earnings.size());
        assertTrue(earnings.get(1).toJson().contains("sessionCount"));
        assertEquals(2, sessions.size());
        assertTrue(sessions.get(1).toJson().contains("avgDuration"));
        assertEquals(2, support.size());
        assertTrue(support.get(1).toJson().contains("$status"));
    }

    private AdminEnhancedDashboardService service() {
        return new AdminEnhancedDashboardService(mock(MongoTemplate.class), mock(AdminMongoSupport.class));
    }

    private Date date(String value) {
        return Date.from(Instant.parse(value));
    }
}
