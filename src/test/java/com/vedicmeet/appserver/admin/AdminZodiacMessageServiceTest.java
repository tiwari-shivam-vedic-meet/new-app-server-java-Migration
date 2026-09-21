package com.vedicmeet.appserver.admin;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.BulkWriteUpsert;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminZodiacMessageServiceTest {

    private static final String ADMIN_ID = "507f1f77bcf86cd799439011";

    @Test
    void createPersistsWhitelistedSchemaDefaultsAndVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).create(Map.of(
                "zodiacSign", "aries",
                "displayName", "Aries",
                "messageTemplate", "Hey {{name}}! welcome today",
                "ignored", "drop-me"), ADMIN_ID);

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertFalse(saved.containsKey("ignored"));
        assertEquals("Make it warm, friendly, and emotionally engaging. Keep the tone uplifting and relatable.",
                saved.get("personalizationInstructions"));
        assertEquals(35, saved.get("wordLimit"));
        assertEquals(true, saved.get("isActive"));
        assertEquals(1, saved.get("version"));
        assertEquals("Aries", out.get("formattedZodiacSign"));
        assertNotNull(out.get("id"));
    }

    @Test
    void createRejectsTemplateWithoutNamePlaceholder() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).create(Map.of("zodiacSign", "aries", "displayName", "Aries",
                        "messageTemplate", "This template has enough words"), ADMIN_ID));

        assertEquals("zodiacMessage validation failed: messageTemplate: Message template must contain {{name}} placeholder for personalization",
                error.getMessage());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void updateThrowsWhenConfigurationMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).update(Map.of("zodiacSign", "aries", "displayName", "Ram"), ADMIN_ID));

        assertEquals("Zodiac message configuration not found", error.getMessage());
    }

    @Test
    void updateReturnsPreUpdateDocumentAndDoesNotSetAdminOrZodiacSign() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        Document before = new Document("_id", new ObjectId()).append("zodiacSign", "aries").append("displayName", "Aries");
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(before);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).update(Map.of("zodiacSign", "aries", "displayName", "Ram"), ADMIN_ID);

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture());
        Document updateDoc = (Document) update.getValue();
        Document set = (Document) updateDoc.get("$set");
        assertEquals("Ram", set.get("displayName"));
        assertFalse(set.containsKey("zodiacSign"));
        assertFalse(set.containsKey("lastUpdatedBy"));
        assertEquals(new Document("version", 1), updateDoc.get("$inc"));
        assertEquals("Aries", out.get("displayName"));
        assertEquals("Aries", out.get("formattedZodiacSign"));
    }

    @Test
    void getThrowsWhenNoActiveConfiguration() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        aggregateReturning(mongo, List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).get(Map.of("zodiacSign", "aries")));

        assertEquals("Zodiac message configuration not found", error.getMessage());
    }

    @Test
    void getReturnsDocumentWithVirtualsAndPopulatedAdminShape() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Document row = new Document("_id", new ObjectId()).append("zodiacSign", "taurus").append("displayName", "Taurus")
                .append("lastUpdatedBy", new Document("name", "Admin").append("email", "a@example.com"));
        aggregateReturning(mongo, List.of(row));

        Map<String, Object> out = service(mongo).get(Map.of("zodiacSign", "taurus"));

        assertEquals("taurus", out.get("zodiacSign"));
        assertEquals("Taurus", out.get("formattedZodiacSign"));
        assertTrue(out.get("lastUpdatedBy") instanceof Document);
        assertNotNull(out.get("id"));
    }

    @Test
    void listRejectsInvalidIsActiveValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mock(MongoTemplate.class)).list(Map.of("isActive", "yes")));

        assertEquals("isActive must be either \"true\" or \"false\"", error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsZodiacMessagesPaginationAndTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = aggregateReturning(mongo, List.of(
                new Document("_id", new ObjectId()).append("zodiacSign", "aries").append("displayName", "Aries")));
        when(coll.countDocuments(any(Bson.class))).thenReturn(13L);

        Map<String, Object> out = service(mongo).list(Map.of("page", "2", "limit", "5", "isActive", "true"));

        assertEquals(13L, out.get("total"));
        assertEquals(2, out.get("page"));
        assertEquals(5, out.get("limit"));
        assertEquals(3L, out.get("totalPages"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("zodiacMessages");
        assertEquals("Aries", list.get(0).get("formattedZodiacSign"));
    }

    @Test
    void deleteThrowsWhenConfigurationMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).delete(Map.of("zodiacSign", "aries"), ADMIN_ID));

        assertEquals("Zodiac message configuration not found", error.getMessage());
    }

    @Test
    void deleteReturnsOnlySuccessMessage() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", new ObjectId()).append("zodiacSign", "aries"));

        Map<String, Object> out = service(mongo).delete(Map.of("zodiacSign", "aries"), ADMIN_ID);

        assertEquals(Map.of("message", "Zodiac message configuration deleted successfully"), out);
    }

    @Test
    void bulkUpdateRejectsMissingMessagesArray() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mock(MongoTemplate.class)).bulkUpdate(Map.of(), ADMIN_ID));

        assertEquals("Messages array is required", error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkUpdateReturnsCountsAndWhitelistsUpdateFields() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo);
        BulkWriteResult result = mock(BulkWriteResult.class);
        when(result.getModifiedCount()).thenReturn(1);
        when(result.getUpserts()).thenReturn(List.of(new BulkWriteUpsert(0, new BsonObjectId(new ObjectId()))));
        when(coll.bulkWrite(anyList())).thenReturn(result);
        ArgumentCaptor<List> writes = ArgumentCaptor.forClass(List.class);

        Map<String, Object> out = service(mongo).bulkUpdate(Map.of("messages", List.of(Map.of(
                "zodiacSign", "aries", "displayName", "Aries", "ignored", "drop"))), ADMIN_ID);

        verify(coll).bulkWrite(writes.capture());
        UpdateOneModel<Document> model = (UpdateOneModel<Document>) writes.getValue().get(0);
        Document update = (Document) model.getUpdate();
        Document set = (Document) update.get("$set");
        assertEquals("aries", ((Document) model.getFilter()).get("zodiacSign"));
        assertEquals("Aries", set.get("displayName"));
        assertFalse(set.containsKey("ignored"));
        assertTrue(set.containsKey("lastUpdatedBy"));
        assertEquals(1, out.get("modifiedCount"));
        assertEquals(1, out.get("upsertedCount"));
        assertEquals("Bulk update completed successfully", out.get("message"));
    }

    @Test
    void personalizedFallsBackToDefaultMessageShapeWhenConfigMissing() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, null);

        Map<String, Object> out = service(mongo).personalized(Map.of("zodiacSign", "aries", "userName", "Mia"));

        assertEquals("Today Looks: Energetic for You", out.get("label"));
        assertTrue(String.valueOf(out.get("description")).startsWith("Hey Mia!"));
        assertEquals("aries", out.get("zodiacSign"));
        assertEquals("Aries", out.get("displayName"));
        assertFalse(out.containsKey("personalizedName"));
    }

    @Test
    void personalizedRejectsInvalidZodiacSign() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mock(MongoTemplate.class)).personalized(Map.of("zodiacSign", "ARIES")));

        assertEquals("Invalid zodiac sign", error.getMessage());
    }

    @Test
    void personalizedReplacesAllNamePlaceholdersAndReportsFlags() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, new Document("zodiacSign", "aries").append("displayName", "Aries")
                .append("messageTemplate", "Hi {{name}} and {{name}}").append("personalizationInstructions", "Warm"));

        Map<String, Object> out = service(mongo).personalized(Map.of("zodiacSign", "aries", "userName", "  Mia  "));

        assertEquals("Warm", out.get("label"));
        assertEquals("Hi Mia and Mia", out.get("description"));
        assertEquals(4, out.get("wordCount"));
        assertEquals("Mia", out.get("personalizedName"));
        assertEquals(true, out.get("isPersonalized"));
    }

    @Test
    void userPersonalizedUsesTestUserFallbackBeforeDelegating() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        collectionReturningFirst(mongo, new Document("zodiacSign", "leo").append("displayName", "Leo")
                .append("messageTemplate", "Hi {{name}}").append("personalizationInstructions", "Royal"));

        Map<String, Object> out = service(mongo).userPersonalized(Map.of("zodiacSign", "leo"));

        assertEquals("Hi Test User", out.get("description"));
        assertEquals("Test User", out.get("personalizedName"));
        assertEquals(true, out.get("isPersonalized"));
    }

    @Test
    void userPersonalizedRequiresZodiacSign() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mock(MongoTemplate.class)).userPersonalized(Map.of("userName", "Mia")));

        assertEquals("Zodiac sign is required", error.getMessage());
    }

    @Test
    void seedPreservesMissingSeederModuleFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mock(MongoTemplate.class)).seed(Map.of(), ADMIN_ID));

        assertEquals("Cannot find module '../../../utils/seeders/zodiac-message-seeder'", error.getMessage());
    }

    @Test
    void statsPreservesMissingSeederModuleFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mock(MongoTemplate.class)).stats(Map.of()));

        assertEquals("Cannot find module '../../../utils/seeders/zodiac-message-seeder'", error.getMessage());
    }

    @Test
    void aggregationPipelinesAreParseableAndUseAdminLookup() {
        AdminZodiacMessageService service = service(mock(MongoTemplate.class));

        List<Document> getPipeline = service.getPipeline("aries");
        List<Document> listPipeline = service.listPipeline(new Document("isActive", true), 5, 5);

        assertDoesNotThrow(() -> getPipeline.forEach(Document::toJson));
        assertDoesNotThrow(() -> listPipeline.forEach(Document::toJson));
        assertTrue(getPipeline.get(1).toJson().contains("\"from\": \"admins\""));
        assertEquals(6, listPipeline.size());
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> aggregateReturning(MongoTemplate mongo, List<Document> rows) {
        MongoCollection<Document> coll = collection(mongo);
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(aggregate).into(any());
        return coll;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collectionReturningFirst(MongoTemplate mongo, Document first) {
        MongoCollection<Document> coll = collection(mongo);
        FindIterable<Document> it = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(it);
        when(it.first()).thenReturn(first);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.ZODIAC_MESSAGES)).thenReturn(coll);
        return coll;
    }

    private AdminZodiacMessageService service(MongoTemplate mongo) {
        return new AdminZodiacMessageService(mongo, new AdminMongoSupport(mongo));
    }
}
