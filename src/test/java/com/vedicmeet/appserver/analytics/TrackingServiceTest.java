package com.vedicmeet.appserver.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingServiceTest {
    @SuppressWarnings("unchecked")
    @Test
    void androidInstallUrlsPersistClickAndCarryClickIdInReferrer() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.CLICK_TRACKINGS)).thenReturn(collection);
        TrackingService service = service(mongo);

        Map<String, Object> result = service.installUrls(Map.of(
                "source", "campaign", "platform", "android"), context("Android"));

        ArgumentCaptor<Document> click = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(click.capture());
        assertEquals("android", click.getValue().getString("platform"));
        assertTrue(String.valueOf(result.get("storeUrl")).contains("referrer="));
        assertTrue(String.valueOf(result.get("deepLink")).contains("click_id="));
        verify(collection).updateOne(any(Document.class), any(Document.class));
    }

    @Test
    void referrerRequiresPlatformAndDeviceUuid() {
        TrackingService service = service(mock(MongoTemplate.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.receiveReferrer(Map.of("platform", "android"), context("Android")));
    }

    private TrackingService service(MongoTemplate mongo) {
        return new TrackingService(mongo, new ObjectMapper(), "https://vedicmeet.com/install",
                "https://play.example/app?id=test", "vedicmeet://", "com.test", "123", 7, 30);
    }

    private TrackingService.ClientContext context(String userAgent) {
        return new TrackingService.ClientContext("127.0.0.1", userAgent, "/track/install",
                "en-IN", "gzip", Map.of());
    }
}
