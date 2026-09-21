package com.vedicmeet.appserver.analytics;

import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {
    @SuppressWarnings("unchecked")
    @Test
    void logEventClassifiesPaymentAndPersistsNodeFieldNames() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.ANALYTICS_LOGS)).thenReturn(collection);
        CleverTapClient cleverTap = mock(CleverTapClient.class);
        when(cleverTap.isReady()).thenReturn(false);
        AnalyticsService service = new AnalyticsService(mongo, cleverTap);

        Map<String, Object> result = service.logEvent(Map.of(
                "event_name", "payment_completed", "userId", "9000000001",
                "event_params", Map.of("source", "app")),
                new AnalyticsService.ClientContext("9000000001", "user", "127.0.0.1", "test", "dev"));

        ArgumentCaptor<Document> row = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(row.capture());
        assertEquals("purchase", row.getValue().getString("event_type"));
        assertEquals("9000000001", row.getValue().getString("user_id"));
        assertEquals("purchase", result.get("event_type"));
    }

    @Test
    void batchRejectsMoreThanOneHundredEventsBeforeWriting() {
        AnalyticsService service = new AnalyticsService(mock(MongoTemplate.class), mock(CleverTapClient.class));
        List<Map<String, Object>> events = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> Map.<String, Object>of("event_name", "screen_view")).toList();
        assertThrows(IllegalArgumentException.class, () -> service.logBatch(Map.of("events", events),
                new AnalyticsService.ClientContext(null, null, null, null, null)));
    }
}
