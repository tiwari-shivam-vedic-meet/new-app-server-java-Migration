package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Mobile port of Node {@code MeditationService} read/play/dashboard methods. */
@Service
public class MeditationService {
    private static final long CACHE_SECONDS = 2 * 60 * 60;
    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;
    private final CleverTapClient cleverTap;

    public MeditationService(MongoTemplate mongo, CacheService cache, AppConstants constants,
                             CleverTapClient cleverTap) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
        this.cleverTap = cleverTap;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> categories(Document actor, Integer pageParam, Integer limitParam) {
        Page page = page(pageParam, limitParam);
        Object actorId = actor.get("_id");
        String key = "meditation:category:list:" + actorId + ":" + page.page + ":" + page.limit;
        Map<String, Object> hit = cache.get(key, Map.class);
        if (hit != null) return hit;
        Document active = new Document("status", true);

        List<Document> list = mongo.getCollection(Collections.MEDITATION_CATEGORY).aggregate(List.of(
                new Document("$match", active),
                new Document("$lookup", new Document("from", Collections.MEDITATION_MEDIA)
                        .append("let", new Document("meditationCategoryId", "$_id"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$eq",
                                        List.of("$meditationCategoryId", "$$meditationCategoryId")))),
                                new Document("$project", mediaProjection())))
                        .append("as", "meditationMedia")),
                new Document("$project", new Document("title", 1).append("status", 1)
                        .append("createdAt", 1).append("meditationImage", media("$image"))
                        .append("meditationMedia", "$meditationMedia")),
                new Document("$sort", new Document("createdAt", 1)),
                new Document("$skip", page.skip()), new Document("$limit", page.limit)))
                .into(new ArrayList<>());

        List<Document> recent = mongo.getCollection(Collections.MEDIA_RECENTS).aggregate(List.of(
                new Document("$match", new Document("status", true).append("userId", actorId)),
                new Document("$lookup", new Document("from", Collections.MEDITATION_MEDIA)
                        .append("let", new Document("meditationMediaId", "$meditationMediaId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$and", List.of(
                                        new Document("$eq", List.of("$_id", "$$meditationMediaId")),
                                        new Document("$eq", List.of("$status", true)))))),
                                new Document("$project", mediaProjection())))
                        .append("as", "meditationMedia")),
                new Document("$unwind", new Document("path", "$meditationMedia")),
                new Document("$project", new Document("recentMeditation", "$meditationMedia")
                        .append("createdAt", 1)),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", page.skip()), new Document("$limit", page.limit)))
                .into(new ArrayList<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("recentList", recent);
        result.put("total", mongo.getCollection(Collections.MEDITATION_CATEGORY).countDocuments(active));
        cache.set(key, result, CACHE_SECONDS);
        return result;
    }

    public void playOrStop(Document actor, String mediaId, String playType, Number timeDuration) {
        ObjectId id = id(mediaId, "meditationMediaId");
        if (!"play".equals(playType) && !"stop".equals(playType))
            throw new IllegalArgumentException("PLAY_TYPE_NOT_VALID");
        if ("stop".equals(playType) && timeDuration == null)
            throw new IllegalArgumentException("timeDuration is required");

        Document media = mongo.getCollection(Collections.MEDITATION_MEDIA)
                .find(new Document("_id", id)).first();
        if (media == null) throw new IllegalArgumentException("MEDITATION_MEDIA_NOT_EXIST");
        uploadEvent(actor);
        Document filter = new Document("userId", actor.get("_id")).append("meditationMediaId", id);
        Document existing = mongo.getCollection(Collections.MEDIA_RECENTS).find(filter).first();
        Date now = new Date();
        if ("play".equals(playType)) {
            if (existing == null) {
                mongo.getCollection(Collections.MEDIA_RECENTS).insertOne(new Document(filter)
                        .append("meditationCategoryId", media.get("meditationCategoryId"))
                        .append("mediaType", "meditation").append("timeDuration", 0)
                        .append("status", true).append("createdAt", now).append("updatedAt", now));
            }
            return;
        }
        if (existing == null) throw new IllegalStateException("MEDITATION_MEDIA_NOT_STARTED");
        int prior = existing.get("timeDuration") instanceof Number n ? n.intValue() : 0;
        mongo.getCollection(Collections.MEDIA_RECENTS).findOneAndUpdate(filter,
                new Document("$set", new Document("userId", actor.get("_id"))
                        .append("meditationMediaId", id)
                        .append("meditationCategoryId", media.get("meditationCategoryId"))
                        .append("timeDuration", prior + timeDuration.intValue()).append("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Map<String, Object> dashboard(Document actor) {
        List<Document> totals = mongo.getCollection(Collections.MEDIA_RECENTS).aggregate(List.of(
                new Document("$match", new Document("userId", actor.get("_id"))),
                new Document("$group", new Document("_id", null)
                        .append("totalMinutes", new Document("$sum", "$timeDuration"))),
                new Document("$project", new Document("_id", 0).append("totalMinutes", 1))))
                .into(new ArrayList<>());
        if (totals.isEmpty()) return new LinkedHashMap<>();
        Object raw = totals.get(0).get("totalMinutes");
        double seconds = raw instanceof Number n ? n.doubleValue() : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        if (seconds < 59) {
            result.put("time", raw == null ? 0 : raw);
            result.put("timeText", "Seconds");
        } else {
            result.put("time", String.format(Locale.ROOT, "%.2f", seconds / 60d));
            result.put("timeText", "Minutes");
        }
        return result;
    }

    private Document mediaProjection() {
        return new Document("title", 1).append("status", 1).append("createdAt", 1)
                .append("meditationImage", media("$image"))
                .append("meditationVideo", media("$video"))
                .append("meditationMantra", media("$mantra"))
                .append("meditationMusic", media("$music")).append("description", 1);
    }

    private Document media(String field) {
        return new Document("$cond", new Document("if", new Document("$ne", List.of(field, "")))
                .append("then", new Document("$concat", List.of(constants.mediaUrl, field)))
                .append("else", ""));
    }

    private void uploadEvent(Document actor) {
        Object details = actor.get("details");
        String phone = details instanceof Document d ? d.getString("phone") : null;
        if (phone != null && cleverTap.isReady()) {
            cleverTap.uploadEvent(phone, "MEDITATION_ACCESS", Map.of("action", "meditation_played",
                    "timestamp", Instant.now().toString(), "source", "server"));
        }
    }

    private ObjectId id(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) throw new IllegalArgumentException(field + " is required");
        return new ObjectId(value);
    }

    private Page page(Integer page, Integer limit) {
        int p = page == null ? 1 : page;
        int l = limit == null ? 10 : limit;
        if (p < 1 || l < 1) throw new IllegalArgumentException("Invalid pagination");
        return new Page(p, l);
    }

    private record Page(int page, int limit) { int skip() { return (page - 1) * limit; } }
}
