package com.vedicmeet.appserver.content;

import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskReminderServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void schedulePersistsFutureReminderAndConvertsIstToUtc() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.JAVA_TASK_REMINDERS)).thenReturn(collection);
        TaskReminderService service = new TaskReminderService(mongo);

        List<String> ids = service.schedule(new ObjectId(), new ObjectId(),
                List.of(LocalDate.now().plusDays(3).toString()),
                List.of(new Document("time", "10:30 AM")), "2",
                List.of("test-token"), "user", "task", "Do remedy");

        assertEquals(1, ids.size());
        ArgumentCaptor<Document> row = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(row.capture());
        assertEquals("PENDING", row.getValue().getString("status"));
        assertEquals(0, row.getValue().getInteger("attempts"));
        assertEquals("task", row.getValue().getString("reminderType"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void cancelOnlyTargetsPendingOrProcessingJobsForSource() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.JAVA_TASK_REMINDERS)).thenReturn(collection);
        TaskReminderService service = new TaskReminderService(mongo);
        ObjectId source = new ObjectId();

        service.cancel(source);

        ArgumentCaptor<Document> filter = ArgumentCaptor.forClass(Document.class);
        verify(collection).updateMany(filter.capture(), any(Document.class));
        assertEquals(source, filter.getValue().getObjectId("sourceId"));
        assertEquals(List.of("PENDING", "PROCESSING"),
                filter.getValue().get("status", Document.class).getList("$in", String.class));
    }

    @Test
    void invalidTimeIsRejectedBeforeAnyReminderIsWritten() {
        TaskReminderService service = new TaskReminderService(mock(MongoTemplate.class));
        assertThrows(IllegalArgumentException.class, () -> service.schedule(
                new ObjectId(), new ObjectId(), List.of(LocalDate.now().plusDays(1).toString()),
                List.of(new Document("time", "not-a-time")), "0", List.of(),
                "user", "task", "title"));
    }
}
