package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Consultant-owned Explore CRUD from production {@code consultant/consultant.js}. */
@Service
public class ConsultantExploreService {

    private static final Set<String> TYPES = Set.of("image", "video", "text");

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;
    private final CallIntegrationOutboxService outbox;

    public ConsultantExploreService(MongoTemplate mongo, CacheService cache, AppConstants constants,
                                    CallIntegrationOutboxService outbox) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document add(Document actor, Map<String, Object> raw, List<String> mediaKeys,
                        String audioKey, String thumbnailKey, String exploreImageKey) {
        ObjectId consultantId = actorId(actor);
        String type = text(raw.getOrDefault("postType", "image")).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("INVALID_POST_TYPE");

        Document post = new Document(raw);
        post.remove("consultantId");
        post.remove("status");
        post.put("consultantId", consultantId);
        post.put("consultantName", display(actor));
        post.put("postType", type);
        post.put("likes", new ArrayList<>());
        post.put("bookmark", new ArrayList<>());
        post.put("notIntrest", new ArrayList<>());
        post.put("notRecommed", new ArrayList<>());
        if (!blank(exploreImageKey)) post.put("exploreImage", exploreImageKey);
        if (!blank(thumbnailKey)) post.put("videoThumbnail", thumbnailKey);

        List<Document> media = new ArrayList<>();
        List<String> keys = mediaKeys == null ? List.of() : mediaKeys;
        for (int index = 0; index < keys.size(); index++) {
            Document item = new Document("type", type.equals("video") ? "video" : "image")
                    .append("url", keys.get(index))
                    .append("heigth", text(raw.get("heigth"))).append("width", text(raw.get("width")))
                    .append("containerRatio", text(raw.get("containerRatio")))
                    .append("containerWidth", text(raw.get("containerWidth")))
                    .append("containerHeight", text(raw.get("containerHeight")));
            if (index == 0 && !blank(audioKey)) item.put("music", audioKey);
            media.add(item);
        }
        if ("text".equals(type)) {
            media.clear();
            post.put("videoThumbnail", "");
        }
        post.put("media", media);

        boolean scheduled = bool(raw.get("isScheduled")) && !blank(text(raw.get("scheduledAt")));
        if (scheduled) {
            post.put("isScheduled", true);
            post.put("scheduledAt", date(raw.get("scheduledAt")));
            post.put("publishedAt", null);
            post.put("status", false);
        } else {
            post.put("isScheduled", false);
            post.put("scheduledAt", null);
            post.put("publishedAt", new Date());
            post.put("status", true);
        }
        Date now = new Date();
        post.put("createdAt", now);
        post.put("updatedAt", now);
        mongo.getCollection(Collections.EXPLORES).insertOne(post);
        cache.invalidate("explore:list:*");
        outbox.enqueue("EXPLORE_CREATED:" + post.getObjectId("_id"), "CONSULTANT_EXPLORE_CREATED",
                new Document("consultantId", consultantId.toHexString())
                        .append("exploreId", post.getObjectId("_id").toHexString())
                        .append("title", text(post.get("title"))));
        return post;
    }

    public Map<String, Object> list(Document actor, int page, int limit, boolean includeInactive) {
        ObjectId consultantId = actorId(actor);
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safePage = Math.max(1, page);
        Document match = new Document("consultantId", consultantId);
        if (!includeInactive) match.put("status", true);
        long count = mongo.getCollection(Collections.EXPLORES).countDocuments(match);
        List<Document> posts = mongo.getCollection(Collections.EXPLORES).find(match)
                .sort(new Document("createdAt", -1)).skip((safePage - 1) * safeLimit)
                .limit(safeLimit).into(new ArrayList<>());
        Document consultant = new Document("_id", consultantId)
                .append("name", actor.get("name")).append("details", actor.get("details"))
                .append("profileImage", media(text(actor.get("profileImage"))));
        posts.forEach(post -> {
            post.put("consultantId", consultant);
            addMediaUrls(post);
        });
        long totalPages = (count + safeLimit - 1) / safeLimit;
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("currentPage", safePage); pagination.put("totalPages", totalPages);
        pagination.put("totalCount", count); pagination.put("hasNextPage", safePage < totalPages);
        pagination.put("hasPrevPage", safePage > 1);
        return Map.of("posts", posts, "pagination", pagination);
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Map<String, Object> setStatus(Document actor, String postId, Object value) {
        ObjectId id = objectId(postId);
        boolean status = bool(value);
        Document changed = mongo.getCollection(Collections.EXPLORES).findOneAndUpdate(
                new Document("_id", id).append("consultantId", actorId(actor)),
                new Document("$set", new Document("status", status).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (changed == null) throw new IllegalStateException("Post not found or you do not have permission to update this post");
        cache.invalidate("explore:list:*");
        return Map.of("postId", postId, "status", status);
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Map<String, Object> delete(Document actor, String postId) {
        Document removed = mongo.getCollection(Collections.EXPLORES).findOneAndDelete(
                new Document("_id", objectId(postId)).append("consultantId", actorId(actor)));
        if (removed == null) throw new IllegalStateException("Post not found or you do not have permission to delete this post");
        cache.invalidate("explore:list:*");
        return Map.of("deleted", true, "postId", postId, "message", "Post deleted successfully");
    }

    private void addMediaUrls(Document post) {
        Object value = post.get("media");
        if (value instanceof List<?> list) for (Object raw : list) {
            if (!(raw instanceof Document item)) continue;
            item.put("url", media(text(item.get("url"))));
            if (!blank(text(item.get("music")))) item.put("music", media(text(item.get("music"))));
            if (!blank(text(item.get("playlistUrl")))) item.put("playlistUrl", media(text(item.get("playlistUrl"))));
        } else if (value instanceof Document item) {
            item.put("url", media(text(item.get("url"))));
        }
        if (!blank(text(post.get("videoThumbnail")))) post.put("videoThumbnail", media(text(post.get("videoThumbnail"))));
        if (!blank(text(post.get("exploreImage")))) post.put("exploreImage", media(text(post.get("exploreImage"))));
    }

    private Date date(Object value) {
        if (value instanceof Date date) return date;
        try { return Date.from(Instant.parse(text(value))); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_SCHEDULED_AT"); }
    }
    private String media(String key) {
        if (blank(key) || key.startsWith("http://") || key.startsWith("https://")) return key;
        return constants.mediaUrl + key;
    }
    private String display(Document actor) {
        String value = text(actor.get("accountName"));
        if (blank(value)) value = text(actor.get("name"));
        return value;
    }
    private ObjectId actorId(Document actor) {
        if (actor == null) throw new IllegalStateException("Invalid token");
        Object value = actor.get("_id");
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(text(value))) return new ObjectId(text(value));
        throw new IllegalStateException("CONSULTANT_NOT_EXIST");
    }
    private ObjectId objectId(String value) {
        if (!blank(value) && ObjectId.isValid(value)) return new ObjectId(value);
        throw new IllegalArgumentException("INVALID_POST_ID");
    }
    private boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
