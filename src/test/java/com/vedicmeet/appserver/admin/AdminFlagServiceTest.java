package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.notification.PushNotificationService;
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

class AdminFlagServiceTest {

    @Test
    void updateRequiresFlagIdBeforeAnyDatabaseWrite() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminFlagService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(Map.of("status", "resolved")));

        assertEquals("FLAG_ID_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void refundRequestRequiresFlagIdBeforeAnyDatabaseWrite() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminFlagService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.refundRequest(Map.of("status", "resolved")));

        assertEquals("FLAG_ID_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void getFlagsRequiresConsultantIdBeforeAnyDatabaseRead() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminFlagService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getFlags(Map.of()));

        assertEquals("CONSULTANT_ID_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void listPipelineStagesAreValidParsedDocuments() {
        List<Document> pipeline = assertDoesNotThrow(() -> AdminFlagService.listPipeline(
                new Document("flag_status", "pending"), new Document("createdAt", -1), 0, 1000));

        assertEquals(14, pipeline.size());
        assertEquals(new Document("flag_status", "pending"), pipeline.get(0).get("$match"));
        assertEquals(new Document("createdAt", -1), pipeline.get(11).get("$sort"));
        assertEquals(0, pipeline.get(12).get("$skip"));
        assertEquals(1000, pipeline.get(13).get("$limit"));
    }

    private AdminFlagService service(MongoTemplate mongo) {
        return new AdminFlagService(mongo, new AdminMongoSupport(mongo), mock(PushNotificationService.class));
    }
}
