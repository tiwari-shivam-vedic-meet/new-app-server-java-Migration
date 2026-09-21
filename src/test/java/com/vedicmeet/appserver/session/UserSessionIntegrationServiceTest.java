package com.vedicmeet.appserver.session;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.vedicmeet.appserver.call.CallExtendService;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSessionIntegrationServiceTest {

    @Test
    void timerExtensionFailureRemainsRetryable() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        CallExtendService calls = mock(CallExtendService.class);
        UserSessionIntegrationService service = new UserSessionIntegrationService(
                mongo, outbox, calls, mock(PushNotificationService.class));
        CallIntegrationOutboxService.ClaimedJob job = job("CALL_TIMER_EXTEND",
                new Document("waitlistId", "W").append("additionalSeconds", 120));

        assertEquals("CALL_TIMER_EXTENSION_FAILED", assertThrows(IllegalStateException.class,
                () -> service.extendTimer(job)).getMessage());
        verify(outbox, never()).markStep(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void quickQueryFansOutDataOnlyPushToEveryOnlineConsultantToken() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> consultants = mock(MongoCollection.class);
        FindIterable<Document> rows = mock(FindIterable.class);
        when(mongo.getCollection(Collections.CONSULTANTS)).thenReturn(consultants);
        when(consultants.find(any(Bson.class))).thenReturn(rows);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new Document("_id", new ObjectId())
                .append("device", new Document("fcmToken", List.of("A", "B"))));
        when(rows.iterator()).thenReturn(cursor);
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        PushNotificationService push = mock(PushNotificationService.class);
        UserSessionIntegrationService service = new UserSessionIntegrationService(
                mongo, outbox, mock(CallExtendService.class), push);
        CallIntegrationOutboxService.ClaimedJob job = job("USER_QUERY_CREATED",
                new Document("waitlistId", "Q").append("userName", "User")
                        .append("queryText", "Career?"));

        service.queryCreated(job);

        verify(push, times(2)).send(eq("cons"), anyString(), anyString(), anyString(),
                anyMap(), isNull(), eq(false), anyMap());
        verify(outbox).markStep("J", "consultant-fanout");
    }

    private CallIntegrationOutboxService.ClaimedJob job(String type, Document payload) {
        return new CallIntegrationOutboxService.ClaimedJob("J", type, payload, 1,
                List.of(), new Document("_id", "J"));
    }
}
