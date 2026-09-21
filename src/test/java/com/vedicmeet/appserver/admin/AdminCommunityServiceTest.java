package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
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

class AdminCommunityServiceTest {

    private static final String COMMUNITY_ID = "507f1f77bcf86cd799439011";
    private static final String USER_ID = "507f1f77bcf86cd799439012";

    @Test
    @SuppressWarnings("unchecked")
    void listUsesActiveFilterAndReturnsListTotalKeys() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COMMUNITIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.sort(any(Bson.class))).thenReturn(find);
        when(find.skip(10)).thenReturn(find);
        when(find.limit(10)).thenReturn(find);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.add(new Document("_id", new ObjectId()).append("title", "Community").append("image", "c.png"));
            return target;
        }).when(find).into(any());
        when(coll.countDocuments(any(Bson.class))).thenReturn(7L);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).list(Map.of("page", "2", "limit", "10", "search", "dev"));

        verify(coll).find(filter.capture());
        Document params = (Document) filter.getValue();
        assertEquals(true, params.get("status"));
        assertTrue(params.toJson().contains(".*dev.*"));
        assertEquals(7L, out.get("total"));
        assertTrue(out.containsKey("list"));
        Document row = (Document) ((List<?>) out.get("list")).get(0);
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/c.png", row.get("communityImage"));
        assertNotNull(row.get("id"));
    }

    @Test
    void addPersistsOnlyCommunitySchemaFieldsDefaultsTimestampsAndVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COMMUNITIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo).add(Map.of(
                "title", "Vedic",
                "description", "Discuss",
                "image", "community/one.png",
                "ignored", "drop-me",
                "status", false));

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertFalse(saved.containsKey("ignored"));
        assertEquals("Vedic", saved.get("title"));
        assertEquals("Discuss", saved.get("description"));
        assertEquals("community/one.png", saved.get("image"));
        assertEquals(false, saved.get("status"));
        assertEquals(List.of(), saved.get("member"));
        assertEquals(List.of(), saved.get("blockUser"));
        assertEquals(List.of(), saved.get("activeMember"));
        assertNotNull(saved.get("createdAt"));
        assertNotNull(saved.get("updatedAt"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/community/one.png", out.get("communityImage"));
        assertNotNull(out.get("id"));
    }

    @Test
    void addChecksDuplicateTitleBeforeImageRequirement() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COMMUNITIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(new Document("title", "Vedic"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).add(Map.of("title", "Vedic")));

        assertEquals("TITLE_EXIST", error.getMessage());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void addRequiresImageWhenNoUploadedImageKeyExists() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COMMUNITIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(mongo).add(Map.of("title", "Vedic", "communityImage", "file-from-validator")));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verify(coll, never()).insertOne(any());
    }

    @Test
    void memberListPipelinePreservesFacetShapeSearchAndSkipPageBug() {
        AdminCommunityService svc = service(mock(MongoTemplate.class));

        List<Document> pipeline = svc.memberListPipeline(COMMUNITY_ID, "2", "5", "raj");

        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        assertEquals(8, pipeline.size());
        assertTrue(pipeline.get(3).toJson().contains("\"from\": \"users\""));
        assertTrue(pipeline.get(5).toJson().contains("https://bucket.s3.ap-south-1.amazonaws.com/"));
        Document facet = (Document) pipeline.get(7).get("$facet");
        List<Document> member = (List<Document>) facet.get("member");
        assertEquals("2", member.get(0).get("$skip"));
        assertEquals("5", member.get(1).get("$limit"));
        assertTrue(pipeline.get(6).toJson().contains(".*raj.*"));
    }

    @Test
    void memberListReturnsMemberAndTotalFromFacet() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Document facet = new Document("member", List.of(new Document("name", "Mia")))
                .append("total", List.of(new Document("count", 1)));
        aggregateReturning(mongo, Collections.COMMUNITIES, List.of(facet));

        Map<String, Object> out = service(mongo).memberList(Map.of("communityId", COMMUNITY_ID));

        assertEquals(1L, out.get("total"));
        assertEquals("Mia", ((Document) ((List<?>) out.get("member")).get(0)).get("name"));
        assertEquals(List.of("total", "member"), new ArrayList<>(out.keySet()));
    }

    @Test
    void memberChatListAlwaysThrowsBecauseChatMessageModelIsUndefinedInNode() {
        // FAITHFUL(node-bug): chatMessageModel = require("../models").chatMessage is undefined (index.js exports
        // no such key), so community.js:200 memberChatList() calls .aggregate() on undefined -> TypeError.
        AdminCommunityService svc = service(mock(MongoTemplate.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.memberChatList(Map.of("communityId", COMMUNITY_ID, "page", 0, "limit", 10)));

        assertEquals("Cannot read properties of undefined (reading 'aggregate')", ex.getMessage());
    }

    @Test
    void changeCommunityUserStatusTogglesBlockListBeforeMissingCommunityThrow() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COMMUNITIES);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(null);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);
        ArgumentCaptor<Bson> mutation = ArgumentCaptor.forClass(Bson.class);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).changeCommunityUserStatus(Map.of("communityId", COMMUNITY_ID, "userId", USER_ID, "status", true)));

        assertEquals("COMMUNITY_NOT_EXIST", error.getMessage());
        verify(coll).findOneAndUpdate(any(Bson.class), mutation.capture());
        Document update = (Document) mutation.getValue();
        assertTrue(update.containsKey("$push"));
        assertEquals(USER_ID, ((Document) update.get("$push")).get("blockUser"));
    }

    @Test
    void changeCommunityUserStatusWhitelistsUpdateFieldsAndReturnsUpdatedDocVirtuals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.COMMUNITIES);
        FindIterable<Document> find = mock(FindIterable.class);
        Document updated = new Document("_id", new ObjectId()).append("title", "Vedic").append("image", "c.png");
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(updated);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(new Document("_id", new ObjectId()));
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).changeCommunityUserStatus(Map.of(
                "communityId", COMMUNITY_ID, "userId", USER_ID, "status", false, "ignored", "drop"));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertEquals(false, set.get("member.$.status"));
        assertTrue(set.containsKey("updatedAt"));
        assertFalse(set.containsKey("ignored"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/c.png", out.get("communityImage"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> aggregateReturning(MongoTemplate mongo, String name, List<Document> rows) {
        MongoCollection<Document> coll = collection(mongo, name);
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
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    private AdminCommunityService service(MongoTemplate mongo) {
        return new AdminCommunityService(mongo, new AdminMongoSupport(mongo), new AppConstants("bucket", "ap-south-1"));
    }
}
