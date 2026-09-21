package com.vedicmeet.appserver.content;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewCommunityServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void personalizedHoroscopeFallsBackToNodeCompatibleMessageWhenNoConfigExists() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> find = mock(FindIterable.class);
        when(mongo.getCollection(Collections.ZODIAC_MESSAGES)).thenReturn(collection);
        when(collection.find(any(Document.class))).thenReturn(find);
        when(find.first()).thenReturn(null);
        NewCommunityService service = new NewCommunityService(mongo,
                mock(PushNotificationService.class));
        Document actor = new Document("_id", new ObjectId()).append("name", "Shivam");

        Map<String, Object> result = service.personalizedHoroscope(
                Map.of("zodiac", "leo"), actor);

        assertEquals("leo", result.get("zodiacSign"));
        assertEquals("Leo", result.get("displayName"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(result.get("description")).contains("Shivam"));
    }

    @Test
    void acceptRejectsUnsupportedActionBeforeDatabaseMutation() {
        NewCommunityService service = new NewCommunityService(mock(MongoTemplate.class),
                mock(PushNotificationService.class));
        Document actor = new Document("_id", new ObjectId());
        assertThrows(IllegalArgumentException.class, () -> service.accept(Map.of(
                "groupId", "family", "otherUserId", new ObjectId().toHexString(),
                "action", "ignore"), actor));
    }
}
