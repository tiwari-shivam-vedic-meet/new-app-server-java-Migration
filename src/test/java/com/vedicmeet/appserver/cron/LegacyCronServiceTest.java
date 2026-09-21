package com.vedicmeet.appserver.cron;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.InteraktClient;
import com.vedicmeet.appserver.integrations.PabblyClient;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LegacyCronServiceTest {

    @Test
    void dailyMaintenanceResetsExactNodeCounters() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> consultants = mock(MongoCollection.class);
        MongoCollection<Document> mappings = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.CONSULTANTS)).thenReturn(consultants);
        when(mongo.getCollection(Collections.CONSULTANT_LEAVE_MESSAGE_MAPPINGS)).thenReturn(mappings);
        when(consultants.updateMany(any(Document.class), any(Document.class)))
                .thenReturn(UpdateResult.acknowledged(3, 3L, null));
        when(mappings.updateMany(any(Document.class), any(Document.class)))
                .thenReturn(UpdateResult.acknowledged(4, 4L, null));

        LegacyCronService.MaintenanceResult result = service(mongo).resetDailyLimits();

        assertEquals(3, result.consultants());
        assertEquals(4, result.mappings());
        ArgumentCaptor<Bson> consultantUpdate = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> mappingUpdate = ArgumentCaptor.forClass(Bson.class);
        verify(consultants).updateMany(any(Bson.class), consultantUpdate.capture());
        verify(mappings).updateMany(any(Bson.class), mappingUpdate.capture());
        Document consultantSet = (Document) ((Document) consultantUpdate.getValue()).get("$set");
        Document mappingSet = (Document) ((Document) mappingUpdate.getValue()).get("$set");
        assertEquals(0, consultantSet.get("limit.liveSessions.current"));
        assertEquals(5, mappingSet.get("userMessageLimit"));
        assertEquals(5, mappingSet.get("consMessageLimit"));
    }

    @Test
    void fixedClockIsConvertedToIndiaTimeForBusinessWindows() {
        LegacyCronService service = service(mock(MongoTemplate.class));
        assertEquals("2026-09-09T12:00", service.nowIst().toString());
    }

    private LegacyCronService service(MongoTemplate mongo) {
        return new LegacyCronService(mongo, mock(CallIntegrationOutboxService.class),
                mock(PabblyClient.class), mock(InteraktClient.class),
                Clock.fixed(Instant.parse("2026-09-09T06:30:00Z"), ZoneOffset.UTC),
                30, 24, 5, 100, 7, 1000, "FREE5MINUTES");
    }
}
