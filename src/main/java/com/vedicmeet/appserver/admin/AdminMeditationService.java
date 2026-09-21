package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminMeditationService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final MediaUploadService uploads;
    private final CacheService cache;
    @SuppressWarnings("unused")
    private final CleverTapClient cleverTap;
    private final AppConstants constants;

    public AdminMeditationService(MongoTemplate mongo, AdminMongoSupport support, MediaUploadService uploads,
                                  CacheService cache, CleverTapClient cleverTap, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.uploads = uploads;
        this.cache = cache;
        this.cleverTap = cleverTap;
        this.constants = constants;
    }

    public Object addMeditationCategory(Map<String, String> body, MultipartFile meditationImage) {
        Map<String, String> input = form(body);
        Document titleExist = mongo.getCollection(Collections.MEDITATION_CATEGORY)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/meditation.js:24 checks duplicate title before requiring image.
        if (meditationImage == null || meditationImage.isEmpty()) throw new IllegalArgumentException("IMAGE_REQUIRE");
        input = new LinkedHashMap<>(input);
        // FAITHFUL(node-quirk): utils/classes/meditation.js:28 uploads category image with type 'meditation'.
        input.put("image", uploads.upload(meditationImage, "meditation"));

        Document doc = categoryCreate(input);
        mongo.getCollection(Collections.MEDITATION_CATEGORY).insertOne(doc);
        // FAITHFUL(node-quirk): utils/classes/meditation.js:36 invalidates category-list and master-data patterns.
        cache.invalidate("meditation:category:list:*");
        cache.invalidate("master:data:*");
        return withMeditationCategoryVirtuals(doc);
    }

    public Object updateMeditationCategory(Map<String, String> body, MultipartFile meditationImage) {
        Map<String, String> input = form(body);
        Object id = support.id(input.get("meditationCategoryId"));
        Document exist = mongo.getCollection(Collections.MEDITATION_CATEGORY).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("MEDITATION_CATEGORY_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/meditation.js:62 duplicate-title query runs even when title is absent.
        Document duplicate = mongo.getCollection(Collections.MEDITATION_CATEGORY)
                .find(new Document("_id", new Document("$nin", List.of(id))).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");

        Map<String, String> enriched = new LinkedHashMap<>(input);
        if (meditationImage != null && !meditationImage.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:66 image upload is optional on update.
            enriched.put("image", uploads.upload(meditationImage, "meditation"));
        }
        // FAITHFUL(node-quirk): utils/classes/meditation.js:73 uses $set: input; Mongoose strict schema drops id/unknown keys.
        Document updated = mongo.getCollection(Collections.MEDITATION_CATEGORY).findOneAndUpdate(
                new Document("_id", id), new Document("$set", categorySet(enriched)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("meditation:category:list:*");
        cache.invalidate("master:data:*");
        return withMeditationCategoryVirtuals(updated);
    }

    public Object blockUnblockMeditationCategory(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("meditationCategoryId"));
        Document exist = mongo.getCollection(Collections.MEDITATION_CATEGORY).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("MEDITATION_CATEGORY_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/meditation.js:109 updates only status from input.
        Document updated = mongo.getCollection(Collections.MEDITATION_CATEGORY).findOneAndUpdate(
                new Document("_id", id),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("meditation:category:list:*");
        cache.invalidate("master:data:*");
        return withMeditationCategoryVirtuals(updated);
    }

    public Object listMedidationCategory(Map<String, String> query) {
        Page page = page(query);
        String search = query == null ? "" : query.getOrDefault("search", "");
        Document params = new Document();
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:144 builds unescaped regex from raw search.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = mongo.getCollection(Collections.MEDITATION_CATEGORY)
                .aggregate(listMedidationCategoryPipeline(params, page.skip, page.limit)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.MEDITATION_CATEGORY).countDocuments(params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    /** {@code [$match,$lookup,$project,$sort,$skip,$limit]} for admin meditation category list. */
    List<Document> listMedidationCategoryPipeline(Document params, int skipIndex, int limit) {
        return List.of(
                new Document("$match", params),
                new Document("$lookup", new Document("from", Collections.MEDITATION_MEDIA)
                        .append("let", new Document("meditationCategoryId", "$_id"))
                        .append("pipeline", List.of(new Document("$match", new Document("$expr",
                                new Document("$eq", List.of("$meditationCategoryId", "$$meditationCategoryId"))))))
                        .append("as", "meditationMedia")),
                new Document("$project", new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("meditationImage", media("$image"))
                        .append("meditationMedia", new Document("$size", "$meditationMedia"))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    public Object getMeditationCategory(Map<String, String> query) {
        Document doc = mongo.getCollection(Collections.MEDITATION_CATEGORY)
                .find(new Document("_id", support.id(query == null ? null : query.get("meditationCategoryId")))).first();
        if (doc == null) throw new IllegalStateException("MEDITATION_CATEGORY_NOT_EXIST");
        return withMeditationCategoryVirtuals(doc);
    }

    public Object addMeditationMedia(Map<String, String> body, MultipartFile meditationVideo, MultipartFile meditationImage,
                                     MultipartFile meditationMantra, MultipartFile meditationMusic) {
        Map<String, String> input = form(body);
        Document category = mongo.getCollection(Collections.MEDITATION_CATEGORY)
                .find(new Document("_id", support.id(input.get("meditationCategoryId")))).first();
        if (category == null) throw new IllegalStateException("MEDITATION_CATEGORY_NOT_EXIST");

        Document titleExist = mongo.getCollection(Collections.MEDITATION_MEDIA)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        Map<String, String> enriched = new LinkedHashMap<>(input);
        if (meditationVideo != null && !meditationVideo.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:230 computes mediaType from mimetype but the GIF guard is commented out.
            String mediaType = subtype(meditationVideo);
            @SuppressWarnings("unused") String unusedMediaType = mediaType;
            String imageGifName = uploads.upload(meditationVideo, "meditation");
            enriched.put("video", imageGifName);
            // FAITHFUL(node-quirk): utils/classes/meditation.js:238 meditationVideo upload also sets image to the same key.
            enriched.put("image", imageGifName);
        }
        if (meditationImage != null && !meditationImage.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:248 meditationImage overwrites image set by meditationVideo when both are present.
            enriched.put("image", uploads.upload(meditationImage, "meditation"));
        }
        if (meditationMantra != null && !meditationMantra.isEmpty()) enriched.put("mantra", uploads.upload(meditationMantra, "meditation"));
        if (meditationMusic != null && !meditationMusic.isEmpty()) enriched.put("music", uploads.upload(meditationMusic, "meditation"));

        Document doc = mediaCreate(enriched);
        mongo.getCollection(Collections.MEDITATION_MEDIA).insertOne(doc);
        return withMeditationMediaVirtuals(doc);
    }

    public Object editMeditationMedia(Map<String, String> body, MultipartFile meditationVideo, MultipartFile meditationImage,
                                      MultipartFile meditationMantra, MultipartFile meditationMusic) {
        Map<String, String> input = form(body);
        Object id = support.id(input.get("meditationMediaId"));
        Document exist = mongo.getCollection(Collections.MEDITATION_MEDIA).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("MEDITATION_MEDIA_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/meditation.js:290 duplicate-title query runs even when title is absent.
        Document duplicate = mongo.getCollection(Collections.MEDITATION_MEDIA)
                .find(new Document("_id", new Document("$nin", List.of(id))).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");

        Map<String, String> enriched = new LinkedHashMap<>(input);
        if (meditationImage != null && !meditationImage.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:294 edit processes meditationImage before meditationVideo.
            enriched.put("image", uploads.upload(meditationImage, "meditation"));
        }
        if (meditationVideo != null && !meditationVideo.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:303 computes mediaType from mimetype but the GIF guard is commented out.
            String mediaType = subtype(meditationVideo);
            @SuppressWarnings("unused") String unusedMediaType = mediaType;
            String imageGifName = uploads.upload(meditationVideo, "meditation");
            enriched.put("video", imageGifName);
            // FAITHFUL(node-quirk): utils/classes/meditation.js:310 edit video upload overwrites image set by meditationImage when both are present.
            enriched.put("image", imageGifName);
        }
        if (meditationMantra != null && !meditationMantra.isEmpty()) enriched.put("mantra", uploads.upload(meditationMantra, "meditation"));
        if (meditationMusic != null && !meditationMusic.isEmpty()) enriched.put("music", uploads.upload(meditationMusic, "meditation"));

        // FAITHFUL(node-quirk): utils/classes/meditation.js:326 uses $set: input; Mongoose strict schema drops id/unknown keys.
        Document updated = mongo.getCollection(Collections.MEDITATION_MEDIA).findOneAndUpdate(
                new Document("_id", id), new Document("$set", mediaSet(enriched)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withMeditationMediaVirtuals(updated);
    }

    public Object blockMeditationMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("meditationMediaId"));
        Document exist = mongo.getCollection(Collections.MEDITATION_MEDIA).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("MEDITATION_MEDIA_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/meditation.js:358 media block does not invalidate cache.
        Document updated = mongo.getCollection(Collections.MEDITATION_MEDIA).findOneAndUpdate(
                new Document("_id", id),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withMeditationMediaVirtuals(updated);
    }

    public Object getMeditationMedia(Map<String, String> query) {
        Document doc = mongo.getCollection(Collections.MEDITATION_MEDIA)
                .find(new Document("_id", support.id(query == null ? null : query.get("meditationMediaId")))).first();
        if (doc == null) throw new IllegalStateException("MEDITATION_MEDIA_NOT_EXIST");
        return withMeditationMediaVirtuals(doc);
    }

    public Object listMeditationMedia(Map<String, String> query) {
        Page page = page(query);
        Document params = new Document("meditationCategoryId", support.id(query == null ? null : query.get("meditationCategoryId")));
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:407 builds unescaped regex from raw search.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }
        // FAITHFUL(node-quirk): utils/classes/meditation.js:413 list uses find().sort().skip().limit(), not aggregation.
        FindIterable<Document> iterable = mongo.getCollection(Collections.MEDITATION_MEDIA).find(params)
                .sort(new Document("createdAt", -1)).skip(page.skip).limit(page.limit);
        List<Document> list = iterable.into(new ArrayList<>()).stream().map(this::withMeditationMediaVirtuals).toList();
        long total = mongo.getCollection(Collections.MEDITATION_MEDIA).countDocuments(params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public Object userListMeditationHistory(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Page page = pageObject(input);
        List<Document> list = mongo.getCollection(Collections.MEDIA_RECENTS)
                .aggregate(userMeditationHistoryListPipeline(input, page.skip, page.limit)).into(new ArrayList<>());
        List<Document> totalResult = mongo.getCollection(Collections.MEDIA_RECENTS)
                .aggregate(userMeditationHistoryCountPipeline(input)).into(new ArrayList<>());
        Object total = totalResult.isEmpty() ? 0 : totalResult.get(0).get("count");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    List<Document> userMeditationHistoryListPipeline(Map<String, Object> input, int skipIndex, int limit) {
        List<Document> pipeline = userMeditationHistoryBasePipeline(userHistoryMatchConditions(input), userHistoryPostGroupMatch(input));
        pipeline.add(new Document("$skip", skipIndex));
        pipeline.add(new Document("$limit", limit));
        return pipeline;
    }

    List<Document> userMeditationHistoryCountPipeline(Map<String, Object> input) {
        List<Document> pipeline = userMeditationHistoryBasePipeline(userHistoryMatchConditions(input), userHistoryPostGroupMatch(input));
        pipeline.add(new Document("$group", new Document("_id", null).append("count", new Document("$sum", 1))));
        return pipeline;
    }

    private List<Document> userMeditationHistoryBasePipeline(Document matchConditions, Document postGroupMatch) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", matchConditions));
        pipeline.add(new Document("$lookup", new Document("from", Collections.MEDITATION_MEDIA)
                .append("localField", "meditationMediaId").append("foreignField", "_id").append("as", "meditation")));
        pipeline.add(new Document("$unwind", new Document("path", "$meditation").append("preserveNullAndEmptyArrays", true)));
        pipeline.add(new Document("$lookup", new Document("from", Collections.USERS)
                .append("localField", "userId").append("foreignField", "_id").append("as", "user")));
        pipeline.add(new Document("$unwind", new Document("path", "$user").append("preserveNullAndEmptyArrays", true)));
        pipeline.add(new Document("$group", new Document("_id", new Document("userId", "$userId").append("meditationMediaId", "$meditationMediaId"))
                .append("userName", new Document("$first", "$user.name"))
                .append("userNameId", new Document("$first", "$user.userId"))
                .append("meditationName", new Document("$first", "$meditation.title"))
                .append("meditationType", new Document("$first", new Document("$cond", List.of(new Document("$eq", List.of("$meditation.type", 0)), "Music", "Mantra"))))
                .append("totalTimeSpent", new Document("$sum", "$timeDuration"))
                .append("totalPlay", new Document("$sum", 1))
                .append("sessions", new Document("$push", new Document("openTime", "$createdAt").append("timeDuration", "$timeDuration")))));
        pipeline.add(new Document("$addFields", new Document("repeatPlay",
                new Document("$cond", List.of(new Document("$gt", List.of("$totalPlay", 1)), new Document("$subtract", List.of("$totalPlay", 1)), 0)))));
        pipeline.add(new Document("$match", postGroupMatch));
        pipeline.add(new Document("$sort", new Document("_id.userId", 1).append("_id.meditationMediaId", 1)));
        pipeline.add(new Document("$project", new Document("userName", 1).append("userId", "$userNameId")
                .append("meditationName", 1).append("meditationType", 1).append("totalTimeSpent", 1)
                .append("totalPlay", 1).append("repeatPlay", 1).append("sessions", 1)));
        pipeline.add(new Document("$unwind", new Document("path", "$sessions").append("preserveNullAndEmptyArrays", false)));
        pipeline.add(new Document("$setWindowFields", new Document("sortBy", new Document("_id", 1))
                .append("output", new Document("serialNumber", new Document("$documentNumber", new Document())))));
        pipeline.add(new Document("$project", new Document("serialNumber", 1).append("_id", 1).append("userName", 1)
                .append("userId", 1).append("meditationOpenTime", "$sessions.openTime")
                .append("totalTimeSpent", "$sessions.timeDuration").append("meditationType", 1)
                .append("meditationName", 1).append("totalPlay", 1).append("repeatPlay", 1)));
        return pipeline;
    }

    Document userHistoryMatchConditions(Map<String, Object> input) {
        Document match = new Document("meditationMediaId", new Document("$exists", true)).append("status", true);
        Map<String, Object> filter = filter(input);
        if (filter.get("startDate") != null && filter.get("endDate") != null) {
            match.append("createdAt", new Document("$gte", date(filter.get("startDate"))).append("$lte", date(filter.get("endDate"))));
        }
        Object timeOfDay = filter.get("timeOfDay");
        if (timeOfDay != null) {
            String tod = String.valueOf(timeOfDay);
            if ("morning".equals(tod)) match.append("$expr", hourAnd(6, 11, true));
            else if ("afternoon".equals(tod)) match.append("$expr", hourAnd(12, 17, true));
            else if ("evening".equals(tod)) match.append("$expr", hourAnd(18, 21, true));
            else if ("night".equals(tod)) {
                // FAITHFUL(node-quirk): utils/classes/meditation.js:481 night is a separate $or branch (>=22 OR <=5).
                match.append("$expr", new Document("$or", List.of(hourCmp("$gte", 22, true), hourCmp("$lte", 5, true))));
            }
        }
        return match;
    }

    Document userHistoryPostGroupMatch(Map<String, Object> input) {
        Document out = new Document();
        Map<String, Object> filter = filter(input);
        if (filter.get("meditationType") != null) out.append("meditationType", filter.get("meditationType"));
        String search = str(input.get("search"));
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:508 trims search then builds unescaped regex over grouped aliases.
            search = search.trim();
            out.append("$or", List.of(regex("userName", search), regex("userNameId", search), regex("meditationName", search)));
        }
        return out;
    }

    public Object getMeditationInsights(Map<String, String> query) {
        Map<String, String> input = form(query);
        List<Document> insights = mongo.getCollection(Collections.MEDIA_RECENTS)
                .aggregate(getMeditationInsightsPipeline(input)).into(new ArrayList<>());
        if (!insights.isEmpty()) return insights.get(0);
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("Mantra", new LinkedHashMap<>(Map.of("totalTimeSpent", 0, "averageTimeSpent", 0)));
        category.put("Music", new LinkedHashMap<>(Map.of("totalTimeSpent", 0, "averageTimeSpent", 0)));
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("totalUsers", 0);
        defaults.put("totalTimeSpent", 0);
        defaults.put("averageTimeSpent", 0);
        defaults.put("repeatUsers", 0);
        defaults.put("categoryMetrics", category);
        return defaults;
    }

    List<Document> getMeditationInsightsPipeline(Map<String, String> input) {
        Document match = new Document("meditationMediaId", new Document("$exists", true)).append("status", true);
        if (input.get("startDate") != null && input.get("endDate") != null) {
            match.append("createdAt", new Document("$gte", date(input.get("startDate"))).append("$lte", date(input.get("endDate"))));
        }
        String tod = input.get("timeOfDay");
        if ("morning".equals(tod)) match.append("$expr", hourAnd(0, 11, false));
        else if ("afternoon".equals(tod)) match.append("$expr", hourAnd(12, 17, false));
        else if ("evening".equals(tod)) match.append("$expr", hourAnd(18, 23, false));
        Document typeMatch = new Document();
        if (input.get("meditationType") != null) {
            // FAITHFUL(node-quirk): utils/classes/meditation.js:717 anything except literal 'Mantra' maps to Music/type 0.
            typeMatch.append("meditation.type", "Mantra".equals(input.get("meditationType")) ? 1 : 0);
        }

        return List.of(
                new Document("$match", match),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$lookup", new Document("from", Collections.MEDITATION_MEDIA)
                        .append("localField", "meditationMediaId").append("foreignField", "_id").append("as", "meditation")),
                new Document("$unwind", new Document("path", "$meditation").append("preserveNullAndEmptyArrays", true)),
                new Document("$match", typeMatch),
                new Document("$group", new Document("_id", null)
                        .append("uniqueUsers", new Document("$addToSet", "$userId"))
                        .append("totalTimeSpent", new Document("$sum", "$timeDuration"))
                        .append("sessionCount", new Document("$sum", 1))
                        .append("sessionsByUser", new Document("$push", new Document("userId", "$userId").append("sessionId", "$_id")))
                        .append("timeByCategory", new Document("$push", new Document("type",
                                new Document("$cond", List.of(new Document("$eq", List.of("$meditation.type", 0)), "Music", "Mantra")))
                                .append("duration", "$timeDuration")))),
                insightsProjectOne(), insightsProjectTwo());
    }

    private Document insightsProjectOne() {
        return new Document("$project", new Document("totalUsers", new Document("$size", "$uniqueUsers"))
                .append("totalTimeSpent", 1)
                .append("averageTimeSpent", new Document("$cond", List.of(new Document("$gt", List.of("$sessionCount", 0)),
                        new Document("$divide", List.of("$totalTimeSpent", "$sessionCount")), 0)))
                .append("repeatUsers", new Document("$size", new Document("$filter", new Document("input",
                        new Document("$setUnion", List.of(new Document("$map", new Document("input", "$sessionsByUser")
                                .append("as", "session").append("in", "$$session.userId"))))).append("as", "user")
                        .append("cond", new Document("$gt", List.of(new Document("$size", new Document("$filter", new Document("input", "$sessionsByUser")
                                .append("as", "s").append("cond", new Document("$eq", List.of("$$s.userId", "$$user"))))), 1))))))
                .append("categoryMetrics", new Document("$reduce", new Document("input", "$timeByCategory")
                        .append("initialValue", new Document("Mantra", new Document("total", 0).append("count", 0))
                                .append("Music", new Document("total", 0).append("count", 0)))
                        .append("in", new Document("Mantra", reduceCategory("Mantra")).append("Music", reduceCategory("Music"))))));
    }

    private Document insightsProjectTwo() {
        return new Document("$project", new Document("totalUsers", 1).append("totalTimeSpent", 1)
                .append("averageTimeSpent", 1).append("repeatUsers", 1)
                .append("categoryMetrics", new Document("Mantra", categoryOut("Mantra")).append("Music", categoryOut("Music"))));
    }

    private Document reduceCategory(String type) {
        return new Document("total", new Document("$cond", List.of(new Document("$eq", List.of("$$this.type", type)),
                new Document("$add", List.of("$$value." + type + ".total", "$$this.duration")), "$$value." + type + ".total")))
                .append("count", new Document("$cond", List.of(new Document("$eq", List.of("$$this.type", type)),
                        new Document("$add", List.of("$$value." + type + ".count", 1)), "$$value." + type + ".count")));
    }

    private Document categoryOut(String type) {
        return new Document("totalTimeSpent", "$categoryMetrics." + type + ".total")
                .append("averageTimeSpent", new Document("$cond", List.of(new Document("$gt", List.of("$categoryMetrics." + type + ".count", 0)),
                        new Document("$divide", List.of("$categoryMetrics." + type + ".total", "$categoryMetrics." + type + ".count")), 0)));
    }

    private Document categoryCreate(Map<String, String> input) {
        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/meditation-category-model.js:7 Mongoose create(input) keeps schema paths and defaults.
        return new Document("_id", new ObjectId()).append("title", str(input.get("title"))).append("image", str(input.get("image")))
                .append("status", true).append("createdAt", now).append("updatedAt", now);
    }

    private Document categorySet(Map<String, String> input) {
        Document set = new Document();
        if (input.containsKey("title")) set.append("title", str(input.get("title")));
        if (input.containsKey("image")) set.append("image", str(input.get("image")));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        set.append("updatedAt", new Date());
        return set;
    }

    private Document mediaCreate(Map<String, String> input) {
        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/meditation-media-model.js:8 Mongoose create(input) keeps schema paths and defaults.
        return new Document("_id", new ObjectId())
                .append("type", intValue(input.get("type"), 0))
                .append("meditationCategoryId", support.id(input.get("meditationCategoryId")))
                .append("title", str(input.get("title"))).append("video", str(input.get("video")))
                .append("mantra", str(input.get("mantra"))).append("music", str(input.get("music")))
                .append("image", str(input.get("image"))).append("description", str(input.get("description")))
                .append("status", true).append("createdAt", now).append("updatedAt", now);
    }

    private Document mediaSet(Map<String, String> input) {
        Document set = new Document();
        if (input.containsKey("type")) set.append("type", intValue(input.get("type"), 0));
        if (input.containsKey("meditationCategoryId")) set.append("meditationCategoryId", support.id(input.get("meditationCategoryId")));
        if (input.containsKey("title")) set.append("title", str(input.get("title")));
        if (input.containsKey("video")) set.append("video", str(input.get("video")));
        if (input.containsKey("mantra")) set.append("mantra", str(input.get("mantra")));
        if (input.containsKey("music")) set.append("music", str(input.get("music")));
        if (input.containsKey("image")) set.append("image", str(input.get("image")));
        if (input.containsKey("description")) set.append("description", str(input.get("description")));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        set.append("updatedAt", new Date());
        return set;
    }

    private Document media(String field) {
        return new Document("$cond", new Document("if", new Document("$ne", List.of(field, "")))
                .append("then", new Document("$concat", List.of(constants.mediaUrl, field))).append("else", ""));
    }

    private Document withMeditationCategoryVirtuals(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = str(source.get("image"));
        result.put("meditationImage", image.isEmpty() ? "" : constants.mediaUrl + image);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Document withMeditationMediaVirtuals(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String video = str(source.get("video"));
        String mantra = str(source.get("mantra"));
        String music = str(source.get("music"));
        String image = str(source.get("image"));
        result.put("meditationVideo", video.isEmpty() ? "" : constants.mediaUrl + video);
        result.put("meditationMantra", mantra.isEmpty() ? "" : constants.mediaUrl + mantra);
        result.put("meditationMusic", music.isEmpty() ? "" : constants.mediaUrl + music);
        result.put("meditationImage", image.isEmpty() ? "" : constants.mediaUrl + image);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Document regex(String key, String value) {
        return new Document(key, new Document("$regex", ".*" + value + ".*").append("$options", "i"));
    }

    private Document hourAnd(int gte, int lte, boolean timezone) {
        return new Document("$and", List.of(hourCmp("$gte", gte, timezone), hourCmp("$lte", lte, timezone)));
    }

    private Document hourCmp(String op, int value, boolean timezone) {
        Object hour = timezone ? new Document("$hour", new Document("date", "$createdAt").append("timezone", "Asia/Kolkata"))
                : new Document("$hour", "$createdAt");
        return new Document(op, List.of(hour, value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filter(Map<String, Object> input) {
        Object filter = input.get("filter");
        return filter instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private Page page(Map<String, String> query) {
        String rawPage = query == null ? null : query.get("page");
        String rawLimit = query == null ? null : query.get("limit");
        // FAITHFUL(node-quirk): utils/classes/meditation.js:134 computes skip before parseInt after default destructuring.
        int page = jsInt(rawPage, 1);
        int limit = jsInt(rawLimit, 10);
        return new Page((page - 1) * limit, limit);
    }

    private Page pageObject(Map<String, Object> input) {
        int page = jsInt(input.get("page"), 1);
        int limit = jsInt(input.get("limit"), 10);
        return new Page((page - 1) * limit, limit);
    }

    private static int jsInt(Object value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(String.valueOf(value).trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static int intValue(Object value, int def) {
        return value == null || String.valueOf(value).isBlank() ? def : jsInt(value, def);
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Date date(Object value) {
        String s = String.valueOf(value);
        try {
            return Date.from(Instant.parse(s));
        } catch (Exception ignored) {
            return Date.from(LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
    }

    private static String subtype(MultipartFile file) {
        String type = file.getContentType();
        int slash = type == null ? -1 : type.indexOf('/');
        return slash >= 0 ? type.substring(slash + 1) : "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, String> form(Map<String, String> body) {
        return body == null ? Map.of() : body;
    }

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private record Page(int skip, int limit) {}
}
