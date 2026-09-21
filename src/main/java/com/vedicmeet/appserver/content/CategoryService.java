package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node CategoryService.listCategory (utils/classes/category.js),
 * served by GET /v1/category. Paginated read with the same status/search handling,
 * the same MEDIA_URL image projection, the same cache key rule (cache only when
 * there is no search), and the same { list, total } result shape.
 */
@Service
public class CategoryService {

    private static final long CACHE_TTL_SECONDS = 60 * 60; // 1 hour, as in Node

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;

    public CategoryService(MongoTemplate mongo, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listCategory(Integer pageParam, Integer limitParam, String search, String status) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        int skipIndex = (page - 1) * limit;
        String searchStr = search == null ? "" : search;

        Document params = new Document();
        Object statusKey = "all";
        if (status != null) {
            boolean statusValue = Boolean.parseBoolean(status.trim());
            params.append("status", statusValue);
            statusKey = statusValue;
        }
        if (!searchStr.isEmpty()) {
            params.append("title", new Document("$regex", ".*" + searchStr + ".*").append("$options", "i"));
        }

        // Cache only when there is no search (search results are dynamic), matching Node.
        String cacheKey = searchStr.isEmpty()
                ? "category:list:" + statusKey + ":" + page + ":" + limit
                : null;
        if (cacheKey != null) {
            Map<String, Object> cached = cache.get(cacheKey, Map.class);
            if (cached != null) {
                return cached;
            }
        }

        Document imageCond = new Document("$cond", new Document()
                .append("if", new Document("$ne", Arrays.asList("$image", "")))
                .append("then", new Document("$concat", Arrays.asList(constants.mediaUrl, "$image")))
                .append("else", ""));

        List<Document> pipeline = Arrays.asList(
                new Document("$match", params),
                new Document("$project", new Document("title", 1).append("status", 1)
                        .append("createdAt", 1).append("categoryImage", imageCond)),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));

        List<Document> list = mongo.getCollection(Collections.CATEGORIES)
                .aggregate(pipeline).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.CATEGORIES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);

        if (cacheKey != null) {
            cache.set(cacheKey, result, CACHE_TTL_SECONDS);
        }
        return result;
    }
}
