package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Faithful read-side port of Node HoroscopeService. */
@Service
public class HoroscopeService {
    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;
    private final HoroscopeProvider provider;

    public HoroscopeService(MongoTemplate mongo, CacheService cache, AppConstants constants,
                            HoroscopeProvider provider) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
        this.provider = provider;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> list(Integer pageParam, Integer limitParam, String search) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        if (page < 1 || limit < 1) throw new IllegalArgumentException("Invalid pagination");
        String term = search == null ? "" : search;
        String cacheKey = term.isEmpty() ? "horoscope:list:" + java.time.LocalDate.now()
                + ":" + page + ":" + limit : null;
        if (cacheKey != null) {
            Map<String, Object> hit = cache.get(cacheKey, Map.class);
            if (hit != null) return hit;
        }
        Document match = new Document();
        if (!term.isEmpty()) match.append("title", new Document("$regex", ".*" + term + ".*")
                .append("$options", "i"));
        Document icon = media("$icon");
        Document background = media("$iconWithBackground");
        List<Document> rows = mongo.getCollection(Collections.HOROSCOPES).aggregate(List.of(
                new Document("$match", match),
                new Document("$project", new Document("title", 1).append("horoscopeIcon", icon)
                        .append("horoscopeIconBackground", background).append("createdAt", 1)),
                new Document("$sort", new Document("createdAt", 1)),
                new Document("$skip", (page - 1) * limit), new Document("$limit", limit)))
                .into(new ArrayList<>());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", mongo.getCollection(Collections.HOROSCOPES).countDocuments(match));
        if (cacheKey != null) {
            long ttl = Math.max(1, Duration.between(ZonedDateTime.now(),
                    ZonedDateTime.now().toLocalDate().plusDays(1).atStartOfDay(
                            ZonedDateTime.now().getZone())).getSeconds());
            cache.set(cacheKey, result, ttl);
        }
        return result;
    }

    public Map<String, Object> details(String horoscopeId, String sign, String type) {
        if (!ObjectId.isValid(horoscopeId)) throw new IllegalArgumentException("HOROSCOPE_NOT_EXIST");
        Document exists = mongo.getCollection(Collections.HOROSCOPES)
                .find(new Document("_id", new ObjectId(horoscopeId))).first();
        if (exists == null) throw new IllegalArgumentException("HOROSCOPE_NOT_EXIST");
        Document details = new Document("_id", exists.get("_id")).append("title", exists.get("title"))
                .append("horoscopeIcon", mediaValue(exists.get("icon")))
                .append("horoscopeIconBackground", mediaValue(exists.get("iconWithBackground")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("details", details);
        result.put("horoscopeDetails", provider.get(sign, type));
        return result;
    }

    private Document media(String field) {
        return new Document("$cond", List.of(new Document("$eq", List.of(field, "")), "",
                new Document("$concat", List.of(constants.mediaUrl, field))));
    }
    private String mediaValue(Object value) {
        String path = value == null ? "" : String.valueOf(value);
        return path.isEmpty() ? "" : constants.mediaUrl + path;
    }
}
