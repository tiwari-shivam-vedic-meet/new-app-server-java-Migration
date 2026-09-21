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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mobile port of Node {@code utils/classes/music.js} methods used by {@code modules/music.js}. */
@Service
public class MusicService {
    private static final long CACHE_SECONDS = 2 * 60 * 60;

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;
    private final CleverTapClient cleverTap;

    public MusicService(MongoTemplate mongo, CacheService cache, AppConstants constants,
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
        String key = "music:category:list:" + actorId + ":" + page.page + ":" + page.limit;
        Map<String, Object> hit = cache.get(key, Map.class);
        if (hit != null) return hit;

        Document active = new Document("status", true);
        List<Document> list = mongo.getCollection(Collections.MUSICS).aggregate(List.of(
                new Document("$match", active),
                new Document("$lookup", new Document("from", Collections.MUSIC_MEDIA)
                        .append("let", new Document("musicId", "$_id"))
                        .append("pipeline", List.of(new Document("$match", new Document("$expr",
                                new Document("$and", List.of(
                                        new Document("$eq", List.of("$musicId", "$$musicId")),
                                        new Document("$eq", List.of("$status", true))))))))
                        .append("as", "musicMedia")),
                new Document("$project", new Document("title", 1).append("status", 1)
                        .append("createdAt", 1).append("musicImage", media("$image"))
                        .append("musicMedia", new Document("$size", "$musicMedia"))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", page.skip()), new Document("$limit", page.limit)))
                .into(new ArrayList<>());

        Document recentFilter = new Document("userId", actorId);
        List<Document> recent = mongo.getCollection(Collections.MEDIA_RECENTS).aggregate(List.of(
                new Document("$match", recentFilter),
                new Document("$lookup", new Document("from", Collections.MUSICS)
                        .append("let", new Document("musicId", "$musicId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$and", List.of(
                                        new Document("$eq", List.of("$_id", "$$musicId")),
                                        new Document("$eq", List.of("$status", true)))))),
                                new Document("$project", new Document("title", 1)
                                        .append("musicImage", media("$image")))))
                        .append("as", "musicCategory")),
                new Document("$unwind", new Document("path", "$musicCategory")
                        .append("preserveNullAndEmptyArrays", true)),
                new Document("$lookup", new Document("from", Collections.MUSIC_MEDIA)
                        .append("let", new Document("musicMediaId", "$musicMediaId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$and", List.of(
                                        new Document("$eq", List.of("$_id", "$$musicMediaId")),
                                        new Document("$eq", List.of("$status", true)))))),
                                new Document("$project", musicMediaProjection())))
                        .append("as", "musicMedia")),
                new Document("$unwind", "$musicMedia"),
                new Document("$match", new Document("musicCategory", new Document("$exists", true))),
                favoriteLookup(actorId),
                new Document("$project", new Document("musicMedia", 1).append("createdAt", 1)
                        .append("updatedAt", 1).append("isfavorites", new Document("$size", "$favorites"))),
                new Document("$sort", new Document("updatedAt", -1)), new Document("$limit", 5)))
                .into(new ArrayList<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("recentList", recent);
        result.put("total", mongo.getCollection(Collections.MUSICS).countDocuments(active));
        cache.set(key, result, CACHE_SECONDS);
        return result;
    }

    public Map<String, Object> media(Document actor, String musicId, Integer pageParam, Integer limitParam) {
        requireId(musicId, "musicId");
        ObjectId id = new ObjectId(musicId);
        Document filter = new Document("$or", List.of(
                new Document("_id", id).append("status", true),
                new Document("musicId", id).append("status", true)));
        List<Document> list = mongo.getCollection(Collections.MUSIC_MEDIA).aggregate(List.of(
                new Document("$match", filter), favoriteLookup(actor.get("_id")),
                new Document("$project", musicMediaProjection()
                        .append("isfavorites", new Document("$size", "$favorites"))),
                new Document("$sort", new Document("createdAt", -1))))
                .into(new ArrayList<>());
        return result(list, mongo.getCollection(Collections.MUSIC_MEDIA).countDocuments(filter));
    }

    public String toggleFavorite(Document actor, String mediaId) {
        ObjectId id = requireId(mediaId, "musicMediaId");
        Document media = mongo.getCollection(Collections.MUSIC_MEDIA)
                .find(new Document("_id", id)).first();
        if (media == null) throw new IllegalArgumentException("MUSIC_MEDIA_NOT_EXIST");
        Document key = new Document("userId", actor.get("_id")).append("musicMediaId", id);
        Document removed = mongo.getCollection(Collections.MEDIA_FAVORITES).findOneAndDelete(key);
        if (removed != null) return "Music remove from favorite";
        Date now = new Date();
        mongo.getCollection(Collections.MEDIA_FAVORITES).insertOne(new Document(key)
                .append("musicId", media.get("musicId")).append("status", true)
                .append("createdAt", now).append("updatedAt", now));
        return "Music added as favorite";
    }

    public Map<String, Object> favorites(Document actor, Integer pageParam, Integer limitParam) {
        Page page = page(pageParam, limitParam);
        Document filter = new Document("userId", actor.get("_id")).append("status", true);
        List<Document> list = mongo.getCollection(Collections.MEDIA_FAVORITES).aggregate(List.of(
                new Document("$match", filter),
                new Document("$lookup", new Document("from", Collections.MUSICS)
                        .append("let", new Document("musicId", "$musicId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$eq",
                                        List.of("$_id", "$$musicId")))),
                                new Document("$project", new Document("title", 1)
                                        .append("musicImage", media("$image")))))
                        .append("as", "musicCategory")),
                new Document("$unwind", new Document("path", "$musicCategory")
                        .append("preserveNullAndEmptyArrays", true)),
                new Document("$lookup", new Document("from", Collections.MUSIC_MEDIA)
                        .append("let", new Document("musicMediaId", "$musicMediaId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$and", List.of(
                                        new Document("$eq", List.of("$_id", "$$musicMediaId")),
                                        new Document("$eq", List.of("$status", true)))))),
                                new Document("$project", musicMediaProjection())))
                        .append("as", "musicMedia")),
                new Document("$unwind", "$musicMedia"),
                new Document("$project", new Document("musicCategory", 1).append("musicMedia", 1)
                        .append("createdAt", 1).append("isfavorites", "1")),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", page.skip()), new Document("$limit", page.limit)))
                .into(new ArrayList<>());
        return result(list, mongo.getCollection(Collections.MEDIA_FAVORITES).countDocuments(filter));
    }

    public void markPlayed(Document actor, String mediaId) {
        ObjectId id = requireId(mediaId, "musicMediaId");
        Document media = mongo.getCollection(Collections.MUSIC_MEDIA)
                .find(new Document("_id", id)).first();
        if (media == null) throw new IllegalArgumentException("MUSIC_MEDIA_NOT_EXIST");
        uploadEvent(actor, "MUSIC_ACCESS", "music_played");
        Document filter = new Document("userId", actor.get("_id")).append("musicMediaId", id)
                .append("musicId", media.get("musicId"));
        Document updated = mongo.getCollection(Collections.MEDIA_RECENTS).findOneAndUpdate(filter,
                new Document("$set", new Document("status", true).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        // Upsert does not copy equality fields nested in all driver/server combinations; set them explicitly.
        if (updated == null) throw new IllegalStateException("RECENT_MEDIA_WRITE_FAILED");
    }

    public Document setMood(Document actor, Number moodAvg) {
        if (moodAvg == null) throw new IllegalArgumentException("moodAvg is required");
        Instant start = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = start.plusSeconds(86_400);
        Document filter = new Document("userId", actor.get("_id")).append("createdAt",
                new Document("$gte", Date.from(start)).append("$lt", Date.from(end)));
        if (mongo.getCollection(Collections.USER_MUSIC_MOODS).find(filter).first() != null)
            throw new IllegalStateException("USER_MUSIC_MOOD_EXIST");
        Date now = new Date();
        Document mood = new Document("userId", actor.get("_id")).append("moodAvg", moodAvg)
                .append("status", true).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.USER_MUSIC_MOODS).insertOne(mood);
        return mood;
    }

    public Map<String, Object> moods(Document actor, String type) {
        Instant from;
        Instant to;
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        if ("day".equals(type)) {
            from = now.atStartOfDay().toInstant(ZoneOffset.UTC);
            to = now.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
        } else if ("week".equals(type)) {
            LocalDate monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            from = monday.atStartOfDay().toInstant(ZoneOffset.UTC);
            to = monday.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
        } else throw new IllegalArgumentException("type must be one of week, day");
        Document filter = new Document("userId", actor.get("_id")).append("createdAt",
                new Document("$gte", Date.from(from)).append("$lte", Date.from(to)));
        List<Document> list = mongo.getCollection(Collections.USER_MUSIC_MOODS).find(filter)
                .projection(new Document("moodAvg", 1).append("createdAt", 1))
                .sort(new Document("createdAt", 1)).into(new ArrayList<>());
        return Map.of("list", list);
    }

    private Document favoriteLookup(Object actorId) {
        return new Document("$lookup", new Document("from", Collections.MEDIA_FAVORITES)
                .append("let", new Document("musicMediaId", "$_id"))
                .append("pipeline", List.of(new Document("$match", new Document("$expr",
                        new Document("$and", List.of(
                                new Document("$eq", List.of("$musicMediaId", "$$musicMediaId")),
                                new Document("$eq", List.of("$userId", actorId))))))))
                .append("as", "favorites"));
    }

    private Document musicMediaProjection() {
        return new Document("title", 1).append("musicDuration", 1)
                .append("musicImage", media("$image")).append("musicMedia", media("$music"))
                .append("createdAt", 1);
    }

    private Document media(String field) {
        return new Document("$cond", new Document("if", new Document("$ne", List.of(field, "")))
                .append("then", new Document("$concat", List.of(constants.mediaUrl, field)))
                .append("else", ""));
    }

    private void uploadEvent(Document actor, String event, String action) {
        Object details = actor.get("details");
        String phone = details instanceof Document d ? d.getString("phone") : null;
        if (phone != null && cleverTap.isReady()) {
            cleverTap.uploadEvent(phone, event, Map.of("action", action,
                    "timestamp", Instant.now().toString(), "source", "server"));
        }
    }

    private ObjectId requireId(String id, String field) {
        if (id == null || !ObjectId.isValid(id)) throw new IllegalArgumentException(field + " is required");
        return new ObjectId(id);
    }

    private Page page(Integer page, Integer limit) {
        int p = page == null ? 1 : page;
        int l = limit == null ? 10 : limit;
        if (p < 1 || l < 1) throw new IllegalArgumentException("Invalid pagination");
        return new Page(p, l);
    }

    private Map<String, Object> result(List<Document> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private record Page(int page, int limit) { int skip() { return (page - 1) * limit; } }
}
