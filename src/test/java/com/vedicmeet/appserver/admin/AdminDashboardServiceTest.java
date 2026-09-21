package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardServiceTest {

    @Test
    void aggregationStageJsonParses() {
        assertTrue(AdminDashboardService.aggregationStageJsonForTests().size() >= 5);
        for (String stage : AdminDashboardService.aggregationStageJsonForTests()) {
            assertNotNull(Document.parse(stage));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void quickStatsReturnsNodeRouteReshapedKeysWithTimestamp() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(mongo.getCollection(anyString())).thenReturn(collection);
        when(collection.countDocuments(any(Document.class))).thenReturn(0L);
        when(collection.aggregate(anyList())).thenReturn(aggregate);
        doAnswer(invocation -> invocation.getArgument(0)).when(aggregate).into(any());
        AdminDashboardService service = new AdminDashboardService(mongo, new AdminMongoSupport(mock(MongoTemplate.class)));

        Map<String, Object> result = service.getQuickStats(Map.of());

        assertEquals(8, result.size());
        assertTrue(result.containsKey("totalSignup"));
        assertTrue(result.containsKey("totalSignout"));
        assertTrue(result.containsKey("totalFreeConsultation"));
        assertTrue(result.containsKey("totalPaidConsultation"));
        assertTrue(result.containsKey("totalRecharge"));
        assertTrue(result.containsKey("totalSpending"));
        assertTrue(result.containsKey("totalWalletBalance"));
        assertNotNull(result.get("timestamp"));
    }

    @Test
    void enableFreeTrailThrowsFaithfulNodeQuirkMessage() {
        AdminDashboardService service = new AdminDashboardService(mock(MongoTemplate.class), new AdminMongoSupport(mock(MongoTemplate.class)));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::enableFreeTrail);

        assertEquals("Cannot read properties of undefined (reading 'status')", error.getMessage());
    }
}
