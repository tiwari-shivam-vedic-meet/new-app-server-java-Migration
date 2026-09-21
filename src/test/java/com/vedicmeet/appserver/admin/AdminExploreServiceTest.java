package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.cache.CacheService;
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

class AdminExploreServiceTest {

    private static final String EXPLORE_ID = "507f1f77bcf86cd799439011";

    @Test
    void listBuildsValidPipelineAndReturnsListTotal() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = aggregateReturning(mongo, Collections.EXPLORES,
                List.of(new Document("title", "Post")));
        when(coll.countDocuments(any(Bson.class))).thenReturn(3L);
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Bson> countFilter = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).list(Map.of("page", "2", "limit", "5", "search", " guru ", "scheduleFilter", "immediate"));

        verify(coll).aggregate(pipeline.capture());
        @SuppressWarnings("unchecked")
        List<Document> stages = pipeline.getValue();
        assertEquals(6, stages.size());
        assertDoesNotThrow(() -> stages.forEach(Document::toJson));
        assertTrue(stages.get(2).toJson().contains("https://bucket.s3.ap-south-1.amazonaws.com/"));
        verify(coll).countDocuments(countFilter.capture());
        assertTrue(((Document) countFilter.getValue()).toJson().contains("isScheduled"));
        assertEquals(3L, out.get("total"));
        assertEquals("Post", ((Document) ((List<?>) out.get("list")).get(0)).get("title"));
    }

    @Test
    void addPersistsWhitelistedExploreDefaultsAndSchedulingQuirk() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.EXPLORES);
        CacheService cache = mock(CacheService.class);
        ArgumentCaptor<Document> insert = ArgumentCaptor.forClass(Document.class);

        Map<String, Object> out = service(mongo, cache).add(Map.of(
                "title", "Scheduled",
                "hastag", "#vedic",
                "description", "desc",
                "isScheduled", "true",
                "scheduledAt", "2026-09-10T10:00:00Z",
                "consultantId", EXPLORE_ID,
                "ignored", "drop-me"));

        verify(coll).insertOne(insert.capture());
        Document saved = insert.getValue();
        assertFalse(saved.containsKey("ignored"));
        assertEquals("Scheduled", saved.get("title"));
        assertEquals(List.of(), saved.get("media"));
        assertEquals(false, saved.get("status"));
        assertEquals(true, saved.get("isScheduled"));
        assertEquals(null, saved.get("publishedAt"));
        assertNotNull(saved.get("scheduledAt"));
        assertNotNull(saved.get("createdAt"));
        assertNotNull(out.get("id"));
        verify(cache).invalidate("explore:list:*");
    }

    @Test
    void addDetailVideoAlwaysThrowsMissingRouteMethodMessage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mock(MongoTemplate.class)).addDetailVideo(Map.of()));

        assertEquals("ExploreService.add_detail_video is not a function", error.getMessage());
    }

    @Test
    void updateThrowsWhenExploreMissingBeforeMutation() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.EXPLORES, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mongo).update(Map.of("exploreId", EXPLORE_ID, "title", "New")));

        assertEquals("EXPLORE_NOT_EXIST", error.getMessage());
        verify(coll, never()).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void updateWhitelistsSetCastsScheduleFalseAndReturnsUpdatedDoc() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.EXPLORES,
                new Document("_id", new ObjectId(EXPLORE_ID)));
        Document updated = new Document("_id", new ObjectId(EXPLORE_ID)).append("title", "New");
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(updated);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).update(Map.of("exploreId", EXPLORE_ID, "title", "New", "isScheduled", "false", "ignored", "drop"));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertEquals("New", set.get("title"));
        assertEquals(false, set.get("isScheduled"));
        assertEquals(null, set.get("scheduledAt"));
        assertNotNull(set.get("publishedAt"));
        assertFalse(set.containsKey("exploreId"));
        assertFalse(set.containsKey("ignored"));
        assertEquals("New", out.get("title"));
        assertNotNull(out.get("id"));
    }

    @Test
    void blockUnblockUpdatesOnlyStatusAndTimestamp() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collectionReturningFirst(mongo, Collections.EXPLORES,
                new Document("_id", new ObjectId(EXPLORE_ID)));
        when(coll.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", new ObjectId(EXPLORE_ID)).append("status", false));
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).blockUnblock(Map.of("exploreId", EXPLORE_ID, "status", "false", "title", "ignored"));

        verify(coll).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertEquals(false, set.get("status"));
        assertTrue(set.containsKey("updatedAt"));
        assertFalse(set.containsKey("title"));
        assertEquals(false, out.get("status"));
    }

    @Test
    void getDetailsPrefixesOnlyMediaUrlAndMusic() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Document row = new Document("_id", new ObjectId(EXPLORE_ID))
                .append("media", new ArrayList<>(List.of(new Document("url", "media.jpg").append("music", "song.mp3"))))
                .append("videoThumbnail", "thumb.jpg")
                .append("exploreImage", "cover.jpg");
        collectionReturningFirst(mongo, Collections.EXPLORES, row);

        Map<String, Object> out = service(mongo).getDetails(Map.of("exploreId", EXPLORE_ID));

        Document media = (Document) ((List<?>) out.get("media")).get(0);
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/media.jpg", media.get("url"));
        assertEquals("https://bucket.s3.ap-south-1.amazonaws.com/song.mp3", media.get("music"));
        assertEquals("thumb.jpg", out.get("videoThumbnail"));
        assertNotNull(out.get("id"));
    }

    @Test
    void getLikesAndCommentsAlwaysThrowsBecauseRouteOmitsUserArg() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mock(MongoTemplate.class)).getLikesAndComments(Map.of("exploreId", EXPLORE_ID)));

        assertEquals("Cannot read properties of undefined (reading '_id')", error.getMessage());
    }

    @Test
    void updateCommentVisibilityAlwaysThrowsUndefinedCapitalModel() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(mock(MongoTemplate.class)).updateCommentVisibility(Map.of("exploreId", EXPLORE_ID, "status", false)));

        assertEquals("CommentModel is not defined", error.getMessage());
    }

    @Test
    void getExploreInsightsUsesValidPipelineAndDefaultWhenEmpty() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminExploreService svc = service(mongo);
        List<Document> pipeline = svc.exploreInsightsPipeline();
        assertEquals(3, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        assertTrue(pipeline.get(0).toJson().contains("comments"));
        aggregateReturning(mongo, Collections.EXPLORES, List.of());

        Map<String, Object> out = svc.getExploreInsights(Map.of());

        assertEquals(0, out.get("totalPosts"));
        assertEquals(0, out.get("engagementRate"));
    }

    @Test
    void getExploreActivityMetricsUsesValidPipelineAndReturnsFirstRow() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminExploreService svc = service(mongo);
        List<Document> pipeline = svc.exploreActivityMetricsPipeline();
        assertEquals(4, pipeline.size());
        assertDoesNotThrow(() -> pipeline.forEach(Document::toJson));
        aggregateReturning(mongo, Collections.EXPLORES,
                List.of(new Document("totalActiveUsers", 2).append("totalLikes", 4).append("totalComments", 3)));

        Map<String, Object> out = svc.getExploreActivityMetrics(Map.of());

        assertEquals(2, out.get("totalActiveUsers"));
        assertEquals(4, out.get("totalLikes"));
    }

    @Test
    void getUserActivityLogBuildsUserPipelinesAndReturnsCount() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> users = collection(mongo, Collections.USERS);
        AggregateIterable<Document> firstAgg = aggregateIterableReturning(List.of(new Document("userName", "Mia")));
        AggregateIterable<Document> secondAgg = aggregateIterableReturning(List.of(new Document("total", 1)));
        when(users.aggregate(anyList())).thenReturn(firstAgg, secondAgg);
        AdminExploreService svc = service(mongo);
        assertDoesNotThrow(() -> svc.userActivityLogBasePipeline("mia").forEach(Document::toJson));

        Map<String, Object> out = svc.getUserActivityLog(Map.of("page", "1", "limit", "10", "search", "mia"));

        assertEquals(1, out.get("total"));
        assertEquals("Mia", ((Document) ((List<?>) out.get("list")).get(0)).get("userName"));
    }

    @Test
    void publishScheduledPostsReturnsNoopMessageWhenNoneDue() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.EXPLORES);
        FindIterable<Document> find = findReturning(List.of());
        when(coll.find(any(Bson.class))).thenReturn(find);

        Map<String, Object> out = service(mongo).publishScheduledPosts();

        assertEquals(0, out.get("publishedCount"));
        assertEquals("No scheduled posts to publish", out.get("message"));
        verify(coll, never()).updateMany(any(Bson.class), any(Bson.class));
    }

    @Test
    void publishScheduledPostsUpdatesDuePostsWithoutSettingStatusTrue() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> coll = collection(mongo, Collections.EXPLORES);
        FindIterable<Document> find = findReturning(List.of(new Document("_id", new ObjectId(EXPLORE_ID)).append("title", "Due")));
        when(coll.find(any(Bson.class))).thenReturn(find);
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(2L);
        when(coll.updateMany(any(Bson.class), any(Bson.class))).thenReturn(updateResult);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);

        Map<String, Object> out = service(mongo).publishScheduledPosts();

        verify(coll).updateMany(any(Bson.class), update.capture());
        Document set = (Document) ((Document) update.getValue()).get("$set");
        assertEquals(false, set.get("isScheduled"));
        assertFalse(set.containsKey("status"));
        assertEquals(2L, out.get("publishedCount"));
        assertEquals("2 scheduled posts published successfully", out.get("message"));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(MongoTemplate mongo, String name) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(mongo.getCollection(name)).thenReturn(coll);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collectionReturningFirst(MongoTemplate mongo, String name, Document first) {
        MongoCollection<Document> coll = collection(mongo, name);
        FindIterable<Document> find = mock(FindIterable.class);
        when(coll.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(first);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> aggregateReturning(MongoTemplate mongo, String name, List<Document> rows) {
        MongoCollection<Document> coll = collection(mongo, name);
        AggregateIterable<Document> aggregate = aggregateIterableReturning(rows);
        when(coll.aggregate(anyList())).thenReturn(aggregate);
        return coll;
    }

    @SuppressWarnings("unchecked")
    private AggregateIterable<Document> aggregateIterableReturning(List<Document> rows) {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(aggregate).into(any());
        return aggregate;
    }

    @SuppressWarnings("unchecked")
    private FindIterable<Document> findReturning(List<Document> rows) {
        FindIterable<Document> find = mock(FindIterable.class);
        doAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.addAll(rows);
            return target;
        }).when(find).into(any());
        return find;
    }

    private AdminExploreService service(MongoTemplate mongo) {
        return service(mongo, mock(CacheService.class));
    }

    private AdminExploreService service(MongoTemplate mongo, CacheService cache) {
        return new AdminExploreService(mongo, new AdminMongoSupport(mongo), cache, new AppConstants("bucket", "ap-south-1"));
    }
}
