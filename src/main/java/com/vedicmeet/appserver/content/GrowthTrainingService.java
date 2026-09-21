package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mobile port of the active (non-commented) Node growth-training methods. */
@Service
public class GrowthTrainingService {
    private final MongoTemplate mongo;
    private final CacheService cache;

    public GrowthTrainingService(MongoTemplate mongo, CacheService cache) {
        this.mongo = mongo;
        this.cache = cache;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> categories(Document actor, Integer pageParam, Integer limitParam) {
        Page page = page(pageParam, limitParam);
        String key = "growth:category:list:" + actor.get("_id") + ":" + page.page + ":" + page.limit;
        Map<String, Object> hit = cache.get(key, Map.class);
        if (hit != null) return hit;
        Document active = new Document("status", true);
        Document recentMatch = new Document("$match", new Document("$expr",
                new Document("$and", List.of(
                        new Document("$eq", List.of("$growthTrainingMediaId", "$$growthTrainingMediaId")),
                        new Document("$eq", List.of("$consId", actor.get("_id")))))));
        Document recentLookup = new Document("$lookup", new Document("from", Collections.MEDIA_RECENTS)
                .append("let", new Document("growthTrainingMediaId", "$_id"))
                .append("pipeline", List.of(recentMatch)).append("as", "recentGrowthData"));
        Document mediaMatch = new Document("$match", new Document("$expr",
                new Document("$and", List.of(
                        new Document("$eq", List.of("$growthTrainingCategoryId", "$$growthTrainingCategoryId")),
                        new Document("$eq", List.of("$status", true))))));
        Document mediaProject = new Document("$project", new Document("title", 1)
                .append("videoLink", 1).append("status", 1).append("createdAt", 1)
                .append("isRead", new Document("$size", "$recentGrowthData")));
        Document mediaLookup = new Document("$lookup", new Document("from", Collections.GROWTH_TRAINING_MEDIA)
                .append("let", new Document("growthTrainingCategoryId", "$_id"))
                .append("pipeline", List.of(mediaMatch, recentLookup, mediaProject,
                        new Document("$sort", new Document("createdAt", -1))))
                .append("as", "growthTrainingMedia"));
        List<Document> list = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).aggregate(List.of(
                new Document("$match", active),
                mediaLookup,
                new Document("$project", new Document("title", 1).append("status", 1)
                        .append("createdAt", 1).append("growthTrainingMedia", 1)),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", page.skip()), new Document("$limit", page.limit)))
                .into(new ArrayList<>());
        Map<String, Object> result = result(list,
                mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).countDocuments(active));
        // Production Node checks this cache key but never writes it. Preserve that observable behavior.
        return result;
    }

    public Map<String, Object> media(String listType, String categoryId,
                                     Integer pageParam, Integer limitParam) {
        if (!"all".equals(listType) && !"recent".equals(listType))
            throw new IllegalArgumentException("listType must be one of all, recent");
        if (!ObjectId.isValid(categoryId == null ? "" : categoryId.trim()))
            throw new IllegalArgumentException("LIST_TYPE_ERROR");
        Page page = page(pageParam, limitParam);
        Document filter = new Document("growthTrainingCategoryId", new ObjectId(categoryId.trim()));
        // The active Node implementation requires a category id for both listType values and does
        // not filter status; this intentionally mirrors that current contract.
        List<Document> list = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).find(filter)
                .sort(new Document("createdAt", -1)).skip(page.skip()).limit(page.limit)
                .into(new ArrayList<>());
        return result(list, mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).countDocuments(filter));
    }

    public void markPlayed(Document actor, String trainingMediaId) {
        ObjectId id = id(trainingMediaId);
        // Node currently checks the category collection with the field named growthTrainingMediaId.
        // Keeping this quirk avoids silently changing which identifier existing clients send.
        Document exists = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)
                .find(new Document("_id", id)).first();
        if (exists == null) throw new IllegalArgumentException("GROWTH_MEDIA_NOT_EXIST");
        Document filter = new Document("consId", actor.get("_id")).append("growthTrainingMediaId", id);
        Date now = new Date();
        mongo.getCollection(Collections.MEDIA_RECENTS).findOneAndUpdate(filter,
                new Document("$set", new Document("status", true).append("updatedAt", now))
                        .append("$setOnInsert", new Document("consId", actor.get("_id"))
                                .append("growthTrainingMediaId", id).append("createdAt", now)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
    }

    public Map<String, Object> checkComplete(Document actor) {
        Document recentMatch = new Document("$match", new Document("$expr",
                new Document("$and", List.of(
                        new Document("$eq", List.of("$growthTrainingMediaId", "$$growthTrainingMediaId")),
                        new Document("$eq", List.of("$consId", actor.get("_id")))))));
        Document lookup = new Document("$lookup", new Document("from", Collections.MEDIA_RECENTS)
                .append("let", new Document("growthTrainingMediaId", "$_id"))
                .append("pipeline", List.of(recentMatch)).append("as", "trainingComplete"));
        List<Document> list = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).aggregate(List.of(
                new Document("$match", new Document("status", true)),
                lookup,
                new Document("$addFields", new Document("isTrainingComplete",
                        new Document("$cond", new Document("if", new Document("$eq", List.of(
                                new Document("$size", "$trainingComplete"), 0)))
                                .append("then", false).append("else", true)))),
                new Document("$project", new Document("trainingComplete", 0))))
                .into(new ArrayList<>());
        boolean complete = list.stream().allMatch(d -> Boolean.TRUE.equals(d.get("isTrainingComplete")));
        return Map.of("isTrainingComplete", complete);
    }

    private ObjectId id(String value) {
        if (value == null || !ObjectId.isValid(value))
            throw new IllegalArgumentException("growthTrainingMediaId is required");
        return new ObjectId(value);
    }

    private Page page(Integer p, Integer l) {
        int page = p == null ? 1 : p;
        int limit = l == null ? 10 : l;
        if (page < 1 || limit < 1) throw new IllegalArgumentException("Invalid pagination");
        return new Page(page, limit);
    }

    private Map<String, Object> result(List<Document> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private record Page(int page, int limit) { int skip() { return (page - 1) * limit; } }
}
