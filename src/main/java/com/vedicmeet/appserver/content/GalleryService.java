package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node GalleryService.listGalleries (utils/classes/gallery.js),
 * served by GET /v1/gallery (PUBLIC in Node). Same paging defaults, same cache key
 * (keyed by consultant id) and 30-minute TTL, same { list, total } shape.
 *
 * The Node route builds the consultant id with `new mongoose.Types.ObjectId(consultantId)`.
 * An invalid id throws (-> caught -> HTTP-200 error envelope); an absent id yields a
 * fresh random ObjectId (matches nothing). Both behaviours are reproduced here.
 */
@Service
public class GalleryService {

    private static final long CACHE_TTL_SECONDS = 30 * 60; // 30 minutes, as in Node

    private final MongoTemplate mongo;
    private final CacheService cache;

    public GalleryService(MongoTemplate mongo, CacheService cache) {
        this.mongo = mongo;
        this.cache = cache;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listGalleries(String consultantId, Integer pageParam,
                                             Integer limitParam, String galleryTypeParam) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        String galleryType = galleryTypeParam == null ? "profile" : galleryTypeParam;
        int skipIndex = (page - 1) * limit;

        // Mirrors `new mongoose.Types.ObjectId(consultantId)`: invalid -> throws,
        // absent -> a fresh id (matches nothing).
        ObjectId consultantOid = (consultantId == null) ? new ObjectId() : new ObjectId(consultantId);

        String cacheKey = "gallery:list:" + consultantOid.toHexString()
                + ":" + galleryType + ":" + page + ":" + limit;

        Map<String, Object> cached = cache.get(cacheKey, Map.class);
        if (cached != null) {
            return cached;
        }

        Document params = new Document("consultantId", consultantOid).append("galleryType", galleryType);

        List<Document> list = mongo.getCollection(Collections.GALLERIES)
                .find(params)
                .sort(new Document("createdAt", -1))
                .skip(skipIndex)
                .limit(limit)
                .into(new ArrayList<>());
        long total = mongo.getCollection(Collections.GALLERIES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);

        cache.set(cacheKey, result, CACHE_TTL_SECONDS);
        return result;
    }
}
