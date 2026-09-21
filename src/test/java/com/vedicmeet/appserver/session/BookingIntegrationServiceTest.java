package com.vedicmeet.appserver.session;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.call.CallLifecycleService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingIntegrationServiceTest {

    @Test
    void callInitiationJobDelegatesExactCorrelationIds() {
        Fixture f = fixture();
        CallIntegrationOutboxService.ClaimedJob job = job("CALL_INITIATE", new Document()
                .append("userId", "U").append("consultantId", "C")
                .append("waitlistId", "W").append("callMode", "audio"));

        f.service.initiate(job);

        verify(f.calls).initiateCall("U", "C", "W", "audio");
    }

    @Test
    void chatFormJobUsesPersistedThreadAndForm() {
        Fixture f = fixture();
        when(f.chat.feedFirstMessageForm(anyMap())).thenReturn(Map.of("success", true));
        Document form = new Document("question", "career");

        f.service.feedChatForm(job("BOOKING_CHAT_FORM", new Document("threadId", "T")
                .append("form", form)));

        verify(f.chat).feedFirstMessageForm(argThat(payload -> "T".equals(payload.get("threadId"))
                && form.equals(payload.get("form"))));
    }

    @Test
    void rejectedChatFormRemainsRetryableByOutboxWorker() {
        Fixture f = fixture();
        when(f.chat.feedFirstMessageForm(anyMap())).thenReturn(Map.of("success", false));

        assertThrows(IllegalStateException.class, () -> f.service.feedChatForm(
                job("BOOKING_CHAT_FORM", new Document("threadId", "T")
                        .append("form", new Document()))));
    }

    @Test
    void instantPostBookingNotifiesConsultantAndRecordsCompletedSteps() {
        Fixture f = fixture();
        ObjectId userId = new ObjectId();
        ObjectId consultantId = new ObjectId();
        findResult(f.users, new Document("_id", userId).append("name", "Asha"));
        findResult(f.consultants, new Document("_id", consultantId)
                .append("device", new Document("fcmToken", List.of("token-1"))));
        when(f.outbox.stepCompleted(any(), anyString())).thenReturn(false);

        f.service.postBooking(job("BOOKING_POST_PROCESSING", new Document("userId", userId)
                .append("consultantId", consultantId).append("waitlistId", "W")
                .append("scheduled", false)));

        verify(f.push).sendNotificationAndCons(eq("cons"), eq("token-1"),
                contains("waitlist"), anyMap(), eq("New User in Waitlist"), eq("cons"));
        verify(f.outbox).markStep("JOB", "consultant-notification");
        verify(f.outbox).markStep("JOB", "traffic-source");
        verify(f.notifications).updateOne(any(Document.class), any(Document.class), any());
    }

    @Test
    void completedStepsAreNotRepeatedOnRetry() {
        Fixture f = fixture();
        ObjectId userId = new ObjectId();
        ObjectId consultantId = new ObjectId();
        findResult(f.users, new Document("_id", userId));
        findResult(f.consultants, new Document("_id", consultantId)
                .append("device", new Document("fcmToken", List.of("token-1"))));
        when(f.outbox.stepCompleted(any(), anyString())).thenReturn(true);

        f.service.postBooking(job("BOOKING_POST_PROCESSING", new Document("userId", userId)
                .append("consultantId", consultantId).append("waitlistId", "W")
                .append("scheduled", false)));

        verifyNoInteractions(f.push);
        verify(f.notifications, never()).updateOne(any(Document.class), any(Document.class), any());
        verify(f.outbox, never()).markStep(anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        CallLifecycleService calls = mock(CallLifecycleService.class);
        PushNotificationService push = mock(PushNotificationService.class);
        ChatServerClient chat = mock(ChatServerClient.class);
        MongoCollection<Document> users = mock(MongoCollection.class);
        MongoCollection<Document> consultants = mock(MongoCollection.class);
        MongoCollection<Document> notifications = mock(MongoCollection.class);
        MongoCollection<Document> explores = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.USERS)).thenReturn(users);
        when(mongo.getCollection(Collections.CONSULTANTS)).thenReturn(consultants);
        when(mongo.getCollection(Collections.NOTIFICATION_RECORDS)).thenReturn(notifications);
        when(mongo.getCollection(Collections.EXPLORES)).thenReturn(explores);
        return new Fixture(new BookingIntegrationService(mongo, outbox, calls, push, chat),
                outbox, calls, push, chat, users, consultants, notifications);
    }

    @SuppressWarnings("unchecked")
    private static void findResult(MongoCollection<Document> collection, Document result) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(result);
    }

    private static CallIntegrationOutboxService.ClaimedJob job(String type, Document payload) {
        return new CallIntegrationOutboxService.ClaimedJob("JOB", type, payload, 1, List.of(),
                new Document("_id", "JOB"));
    }

    private record Fixture(BookingIntegrationService service,
                           CallIntegrationOutboxService outbox,
                           CallLifecycleService calls,
                           PushNotificationService push,
                           ChatServerClient chat,
                           MongoCollection<Document> users,
                           MongoCollection<Document> consultants,
                           MongoCollection<Document> notifications) {}
}
