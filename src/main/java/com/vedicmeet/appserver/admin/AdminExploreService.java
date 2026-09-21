package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminExploreService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final CacheService cache;
    private final AppConstants constants;

    public AdminExploreService(MongoTemplate mongo, AdminMongoSupport support, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.cache = cache;
        this.constants = constants;
    }

    public Map<String, Object> list(Map<String, String> query) {
        Page page = page(query);
        Document params = listParams(query);

        List<Document> list = mongo.getCollection(Collections.EXPLORES)
                .aggregate(listPipeline(params, page.skip, page.limit)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.EXPLORES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> add(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        String postType = present(input.get("postType")) ? str(input.get("postType")).toLowerCase() : "image";

        // FAITHFUL(node-quirk): utils/classes/explore.js:68-139 Java controller has no req.files, so file upload branches are skipped and media starts empty.
        input.put("media", List.of());
        if ("text".equals(postType)) input.put("videoThumbnail", "");
        input.put("postType", postType);

        if ("true".equals(input.get("isScheduled")) && present(input.get("scheduledAt"))) {
            // FAITHFUL(node-quirk): utils/classes/explore.js:142-146 only the literal string 'true' schedules a post.
            input.put("scheduledAt", date(input.get("scheduledAt")));
            input.put("publishedAt", null);
            input.put("status", false);
        } else {
            // FAITHFUL(node-quirk): utils/classes/explore.js:147-151 every non-string-true value publishes immediately.
            input.put("isScheduled", false);
            input.put("scheduledAt", null);
            input.put("publishedAt", new Date());
            input.put("status", true);
        }

        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/explore-model.js:5-116 Model.create(input) keeps only schema paths/defaults; hastag has misspelled `deault` so no default is added.
        Document doc = exploreCreateDoc(input, now);
        mongo.getCollection(Collections.EXPLORES).insertOne(doc);
        cache.invalidate("explore:list:*");
        // FAITHFUL(node-quirk): utils/classes/explore.js:222-247 follower FCM send is asynchronous external transport; local DB side effect is complete and transport is a no-op seam here.
        return withExploreVirtuals(doc);
    }

    public Map<String, Object> addDetailVideo(Map<String, Object> body) {
        // FAITHFUL(node-bug): rest-apis/modules/admin/explore.js:43 calls ExploreService.add_detail_video(...), but utils/classes/explore.js exports no such method.
        throw new IllegalStateException("ExploreService.add_detail_video is not a function");
    }

    public Map<String, Object> update(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document existing = mongo.getCollection(Collections.EXPLORES)
                .find(new Document("_id", support.id(input.get("exploreId")))).first();
        if (existing == null) throw new IllegalStateException("EXPLORE_NOT_EXIST");

        if ("true".equals(input.get("isScheduled")) && present(input.get("scheduledAt"))) {
            // FAITHFUL(node-quirk): utils/classes/explore.js:357-360 scheduled updates clear publishedAt but do not force status false.
            input.put("scheduledAt", date(input.get("scheduledAt")));
            input.put("publishedAt", null);
        } else if ("false".equals(input.get("isScheduled"))) {
            // FAITHFUL(node-quirk): utils/classes/explore.js:361-365 only literal string 'false' immediately publishes during update.
            input.put("isScheduled", false);
            input.put("scheduledAt", null);
            input.put("publishedAt", new Date());
        }

        // FAITHFUL(node-quirk): utils/classes/explore.js:368-372 $set: input is Mongoose-strict, so exploreId and unknown keys are ignored.
        Document set = exploreUpdateDoc(input);
        set.put("updatedAt", new Date());
        Document updated = mongo.getCollection(Collections.EXPLORES).findOneAndUpdate(
                new Document("_id", support.id(input.get("exploreId"))), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("explore:list:*");
        return withExploreVirtuals(updated);
    }

    public Map<String, Object> blockUnblock(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document existing = mongo.getCollection(Collections.EXPLORES)
                .find(new Document("_id", support.id(input.get("exploreId")))).first();
        if (existing == null) throw new IllegalStateException("EXPLORE_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/explore.js:397-401 updates only status, with Mongoose timestamps adding updatedAt.
        Document updated = mongo.getCollection(Collections.EXPLORES).findOneAndUpdate(
                new Document("_id", support.id(input.get("exploreId"))),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("explore:list:*");
        return withExploreVirtuals(updated);
    }

    public Map<String, Object> getDetails(Map<String, String> query) {
        Document existing = mongo.getCollection(Collections.EXPLORES)
                .find(new Document("_id", support.id(query == null ? null : query.get("exploreId")))).first();
        if (existing == null) throw new IllegalStateException("EXPLORE_NOT_EXIST");

        // FAITHFUL(node-bug): utils/classes/explore.js:548-565 prefixes media urls in-place but does not prefix videoThumbnail/exploreImage here.
        Document result = withExploreVirtuals(existing);
        prefixMediaInPlace(result);
        return result;
    }

    public Map<String, Object> getLikesAndComments(Map<String, String> query) {
        // FAITHFUL(node-bug): rest-apis/modules/admin/explore.js:100 passes no user arg; utils/classes/explore.js:678 reads user._id before any DB work.
        throw new IllegalStateException("Cannot read properties of undefined (reading '_id')");
    }

    public Map<String, Object> updateCommentVisibility(Map<String, Object> body) {
        // FAITHFUL(node-bug): utils/classes/explore.js:852 references capital CommentModel, which is never defined/imported.
        throw new IllegalStateException("CommentModel is not defined");
    }

    public Map<String, Object> getExploreInsights(Map<String, String> query) {
        List<Document> rows = mongo.getCollection(Collections.EXPLORES)
                .aggregate(exploreInsightsPipeline()).into(new ArrayList<>());
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("totalPosts", 0);
        defaults.put("totalLikes", 0);
        defaults.put("totalComments", 0);
        defaults.put("engagementRate", 0);
        return defaults;
    }

    public Map<String, Object> getExploreActivityMetrics(Map<String, String> query) {
        List<Document> rows = mongo.getCollection(Collections.EXPLORES)
                .aggregate(exploreActivityMetricsPipeline()).into(new ArrayList<>());
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("totalActiveUsers", 0);
        defaults.put("totalTimeSpent", 0);
        defaults.put("totalLikes", 0);
        defaults.put("totalComments", 0);
        return defaults;
    }

    public Map<String, Object> getUserActivityLog(Map<String, String> query) {
        Page page = page(query);
        String search = query == null ? "" : query.getOrDefault("search", "");
        List<Document> activityPipeline = userActivityLogBasePipeline(search);
        activityPipeline.add(new Document("$skip", page.skip));
        activityPipeline.add(new Document("$limit", page.limit));
        List<Document> activityLog = mongo.getCollection(Collections.USERS).aggregate(activityPipeline).into(new ArrayList<>());

        List<Document> countPipeline = userActivityLogBasePipeline(search);
        countPipeline.add(new Document("$count", "total"));
        List<Document> totalRows = mongo.getCollection(Collections.USERS).aggregate(countPipeline).into(new ArrayList<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", activityLog);
        result.put("total", totalRows.isEmpty() ? 0 : totalRows.get(0).get("total"));
        return result;
    }

    public Map<String, Object> publishScheduledPosts() {
        Date now = new Date();
        Document filter = new Document("isScheduled", true)
                .append("scheduledAt", new Document("$lte", now))
                .append("publishedAt", null);
        List<Document> scheduledPosts = mongo.getCollection(Collections.EXPLORES).find(filter).into(new ArrayList<>());
        if (scheduledPosts.isEmpty()) {
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("publishedCount", 0);
            none.put("message", "No scheduled posts to publish");
            return none;
        }

        // FAITHFUL(node-bug): utils/classes/explore.js:2015-2021 publishes due posts but does not set status true, so posts created with status false stay hidden.
        UpdateResult update = mongo.getCollection(Collections.EXPLORES).updateMany(filter,
                new Document("$set", new Document("publishedAt", now)
                        .append("isScheduled", false)
                        .append("updatedAt", now)));
        // FAITHFUL(node-quirk): utils/classes/explore.js:2024-2040 FCM notification per published post is external transport; no-op seam.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publishedCount", update.getModifiedCount());
        result.put("message", update.getModifiedCount() + " scheduled posts published successfully");
        return result;
    }

    /** {@code [$match,$addFields,$project,$sort,$skip,$limit]} for admin explore list. */
    List<Document> listPipeline(Document params, int skipIndex, int limit) {
        Document mediaUrlPrefix = new Document("$cond", List.of(
                new Document("$eq", List.of("$$mediaItem.url", "")),
                "",
                new Document("$concat", List.of(constants.mediaUrl, "$$mediaItem.url"))));
        Document mappedMedia = new Document("$map", new Document("input", "$media")
                .append("as", "mediaItem")
                .append("in", new Document("$mergeObjects", List.of("$$mediaItem", new Document("url", mediaUrlPrefix)))));
        return List.of(
                new Document("$match", params),
                new Document("$addFields", new Document("media", new Document("$cond", List.of(
                        new Document("$isArray", "$media"), mappedMedia, "$media")))),
                new Document("$project", new Document("title", 1).append("hastag", 1).append("description", 1)
                        .append("status", 1).append("createdAt", 1).append("media", 1).append("postType", 1)
                        .append("scheduledAt", 1).append("isScheduled", 1).append("publishedAt", 1)
                        .append("videoThumbnail", media("$videoThumbnail"))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    /** {@code [$lookup,$group,$project]} for global explore insights. */
    List<Document> exploreInsightsPipeline() {
        return List.of(
                new Document("$lookup", new Document("from", Collections.COMMENTS)
                        .append("localField", "_id").append("foreignField", "exploreId").append("as", "comments")),
                Document.parse("{ $group: { _id: null, totalPosts: { $sum: 1 }, totalLikes: { $sum: { $size: '$likes' } }, totalComments: { $sum: { $add: [ { $size: '$comments' }, { $sum: { $map: { input: '$comments', as: 'comment', in: { $size: '$$comment.reply' } } } } ] } }, totalEngagements: { $sum: { $add: [ { $size: '$likes' }, { $size: '$comments' }, { $sum: { $map: { input: '$comments', as: 'comment', in: { $size: '$$comment.reply' } } } } ] } } } }"),
                Document.parse("{ $project: { totalPosts: 1, totalLikes: 1, totalComments: 1, engagementRate: { $cond: [ { $gt: ['$totalPosts', 0] }, { $divide: ['$totalEngagements', '$totalPosts'] }, 0 ] } } }")
        );
    }

    /** {@code [$match,$lookup,$group,$project]} for user activity metrics. */
    List<Document> exploreActivityMetricsPipeline() {
        return List.of(
                new Document("$match", new Document("status", true)),
                new Document("$lookup", new Document("from", Collections.COMMENTS)
                        .append("localField", "_id").append("foreignField", "exploreId").append("as", "comments")),
                Document.parse("{ $group: { _id: null, totalLikes: { $sum: { $size: '$likes' } }, totalTimeSpent: { $sum: { $size: '$likes' } }, totalComments: { $sum: { $add: [ { $size: '$comments' }, { $sum: { $map: { input: '$comments', as: 'comment', in: { $size: '$$comment.reply' } } } } ] } }, activeUsers: { $addToSet: { $concatArrays: [ '$likes.userId', '$comments.userId', { $reduce: { input: '$comments', initialValue: [], in: { $concatArrays: [ '$$value', { $map: { input: '$$this.reply', as: 'reply', in: '$$reply.userId' } } ] } } } ] } } } }"),
                Document.parse("{ $project: { totalActiveUsers: { $size: { $setUnion: ['$activeUsers'] } }, totalTimeSpent: 1, totalLikes: 1, totalComments: 1 } }")
        );
    }

    /** Base pipeline from utils/classes/explore.js:987-1138; caller appends pagination or count. */
    List<Document> userActivityLogBasePipeline(String search) {
        Document userMatch = new Document("status", true).append("isDeleted", false);
        if (present(search)) {
            String trimmed = search.trim();
            // FAITHFUL(node-quirk): utils/classes/explore.js:970-979 builds unescaped regexes after trim.
            userMatch.put("$or", List.of(new Document("name", regex(trimmed)), new Document("email", regex(trimmed))));
        }
        return new ArrayList<>(List.of(
                new Document("$match", userMatch),
                new Document("$lookup", new Document("from", Collections.EXPLORES)
                        .append("let", new Document("userId", "$_id"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("status", true)),
                                new Document("$unwind", "$likes"),
                                new Document("$match", new Document("$expr", new Document("$eq", List.of("$likes.userId", "$$userId")))),
                                new Document("$project", new Document("likeDate", "$likes.date").append("exploreId", "$_id"))))
                        .append("as", "userLikes")),
                new Document("$lookup", new Document("from", Collections.COMMENTS)
                        .append("localField", "_id").append("foreignField", "userId").append("as", "userComments")),
                Document.parse("{ $project: { userName: '$name', userId: '$userId', mailId: '$email', likesCount: { $size: '$userLikes' }, commentsCount: { $sum: [ { $size: '$userComments' }, { $sum: { $map: { input: '$userComments', as: 'comment', in: { $size: '$$comment.reply' } } } } ] } } }"),
                Document.parse("{ $group: { _id: null, records: { $push: { userName: '$userName', userId: '$userId', mailId: '$mailId', openingDateTime: null, totalTimeSpent: 0, likes: '$likesCount', comments: '$commentsCount' } } } }"),
                Document.parse("{ $unwind: { path: '$records', includeArrayIndex: 'sNo' } }"),
                Document.parse("{ $project: { sNo: { $add: ['$sNo', 1] }, userName: '$records.userName', userId: '$records.userId', mailId: '$records.mailId', openingDateTime: '$records.openingDateTime', totalTimeSpent: '$records.totalTimeSpent', likes: '$records.likes', comments: '$records.comments' } }")
        ));
    }

    private Document listParams(Map<String, String> query) {
        Document params = new Document();
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (present(search)) {
            String trimmed = search.trim();
            // FAITHFUL(node-quirk): utils/classes/explore.js:433-442 trims search and uses unescaped regexes.
            params.put("$or", List.of(new Document("title", regex(trimmed)), new Document("hastag", regex(trimmed)),
                    new Document("description", regex(trimmed))));
        }
        String postType = query == null ? "" : query.getOrDefault("postType", "");
        if (present(postType)) params.put("postType", postType);

        String scheduleFilter = query == null ? "" : query.getOrDefault("scheduleFilter", "");
        if (present(scheduleFilter)) {
            if ("immediate".equals(scheduleFilter)) {
                // FAITHFUL(node-bug): utils/classes/explore.js:449-457 Object.assign replaces any prior search $or with this schedule $or.
                params.put("$or", List.of(new Document("isScheduled", new Document("$exists", false)),
                        new Document("isScheduled", false), new Document("scheduledAt", new Document("$exists", false))));
            } else if ("scheduled".equals(scheduleFilter)) {
                params.put("isScheduled", true);
                params.put("scheduledAt", new Document("$exists", true).append("$ne", null));
            }
        }
        return params;
    }

    private Document exploreCreateDoc(Map<String, Object> input, Date now) {
        Document doc = new Document("_id", new ObjectId())
                .append("title", input.containsKey("title") ? str(input.get("title")) : "")
                .append("media", mediaList(input.get("media")))
                .append("exploreImage", input.containsKey("exploreImage") ? str(input.get("exploreImage")) : "");
        if (input.containsKey("hastag")) doc.append("hastag", str(input.get("hastag")));
        doc.append("description", input.containsKey("description") ? str(input.get("description")) : "")
                .append("status", input.containsKey("status") ? toBool(input.get("status")) : true)
                .append("notRecommed", objectIdList(input.get("notRecommed")))
                .append("likes", likesList(input.get("likes")))
                .append("bookmark", objectIdList(input.get("bookmark")))
                .append("notIntrest", objectIdList(input.get("notIntrest")))
                .append("report", objectIdList(input.get("report")))
                .append("videoThumbnail", input.containsKey("videoThumbnail") ? str(input.get("videoThumbnail")) : "")
                .append("postType", input.containsKey("postType") ? str(input.get("postType")) : "image")
                .append("isScheduled", input.containsKey("isScheduled") ? toBool(input.get("isScheduled")) : false)
                .append("scheduledAt", input.containsKey("scheduledAt") ? input.get("scheduledAt") : null)
                .append("publishedAt", input.containsKey("publishedAt") ? input.get("publishedAt") : null)
                .append("consultantId", present(input.get("consultantId")) ? support.id(input.get("consultantId")) : null)
                .append("stats", stats(input.get("stats")))
                .append("createdAt", now).append("updatedAt", now);
        return doc;
    }

    private Document exploreUpdateDoc(Map<String, Object> input) {
        Document set = new Document();
        if (input.containsKey("title")) set.put("title", str(input.get("title")));
        if (input.containsKey("media")) set.put("media", mediaList(input.get("media")));
        if (input.containsKey("exploreImage")) set.put("exploreImage", str(input.get("exploreImage")));
        if (input.containsKey("hastag")) set.put("hastag", str(input.get("hastag")));
        if (input.containsKey("description")) set.put("description", str(input.get("description")));
        if (input.containsKey("status")) set.put("status", toBool(input.get("status")));
        if (input.containsKey("notRecommed")) set.put("notRecommed", objectIdList(input.get("notRecommed")));
        if (input.containsKey("likes")) set.put("likes", likesList(input.get("likes")));
        if (input.containsKey("bookmark")) set.put("bookmark", objectIdList(input.get("bookmark")));
        if (input.containsKey("notIntrest")) set.put("notIntrest", objectIdList(input.get("notIntrest")));
        if (input.containsKey("report")) set.put("report", objectIdList(input.get("report")));
        if (input.containsKey("videoThumbnail")) set.put("videoThumbnail", str(input.get("videoThumbnail")));
        if (input.containsKey("postType")) set.put("postType", str(input.get("postType")));
        if (input.containsKey("isScheduled")) set.put("isScheduled", toBool(input.get("isScheduled")));
        if (input.containsKey("scheduledAt")) set.put("scheduledAt", input.get("scheduledAt"));
        if (input.containsKey("publishedAt")) set.put("publishedAt", input.get("publishedAt"));
        if (input.containsKey("consultantId")) set.put("consultantId", present(input.get("consultantId")) ? support.id(input.get("consultantId")) : null);
        if (input.containsKey("stats")) set.put("stats", stats(input.get("stats")));
        return set;
    }

    @SuppressWarnings("unchecked")
    private List<Document> mediaList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Document> out = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> item = (Map<String, Object>) map;
            Document media = new Document();
            if (item.containsKey("type")) media.put("type", str(item.get("type")));
            if (item.containsKey("url")) media.put("url", str(item.get("url")));
            if (item.containsKey("playlistUrl")) media.put("playlistUrl", str(item.get("playlistUrl")));
            if (item.containsKey("processedPrefix")) media.put("processedPrefix", str(item.get("processedPrefix")));
            if (item.containsKey("status")) media.put("status", str(item.get("status")));
            if (item.containsKey("music")) media.put("music", str(item.get("music")));
            if (item.containsKey("heigth")) media.put("heigth", str(item.get("heigth")));
            if (item.containsKey("width")) media.put("width", str(item.get("width")));
            media.put("containerRatio", item.containsKey("containerRatio") ? str(item.get("containerRatio")) : "");
            media.put("containerWidth", item.containsKey("containerWidth") ? str(item.get("containerWidth")) : "");
            media.put("containerHeight", item.containsKey("containerHeight") ? str(item.get("containerHeight")) : "");
            media.put("videoId", item.containsKey("videoId") ? str(item.get("videoId")) : "");
            out.add(media);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Document> likesList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Document> out = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> like = (Map<String, Object>) map;
            Document doc = new Document();
            if (like.containsKey("userId")) doc.put("userId", support.id(like.get("userId")));
            doc.put("date", like.containsKey("date") ? date(like.get("date")) : new Date());
            out.add(doc);
        }
        return out;
    }

    private List<Object> objectIdList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return list.stream().map(support::id).toList();
    }

    @SuppressWarnings("unchecked")
    private Document stats(Object value) {
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document("totalViews", 0).append("totalConsultantBookingChat", 0).append("totalConsultantBookingAudio", 0);
    }

    private Document media(String field) {
        return new Document("$cond", List.of(new Document("$eq", List.of(field, "")), "",
                new Document("$concat", List.of(constants.mediaUrl, field))));
    }

    private Document regex(String value) {
        return new Document("$regex", ".*" + value + ".*").append("$options", "i");
    }

    private void prefixMediaInPlace(Document doc) {
        Object media = doc.get("media");
        if (media instanceof Document item && present(item.get("url"))) {
            item.put("url", constants.mediaUrl + item.get("url"));
        }
        if (media instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Document item) {
                    if (present(item.get("url"))) item.put("url", constants.mediaUrl + item.get("url"));
                    if (present(item.get("music"))) item.put("music", constants.mediaUrl + item.get("music"));
                }
            }
        }
    }

    private Document withExploreVirtuals(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Page page(Map<String, String> query) {
        Object rawPage = query == null || !query.containsKey("page") ? 1 : query.get("page");
        Object rawLimit = query == null || !query.containsKey("limit") ? 10 : query.get("limit");
        int page = jsInt(rawPage, 1);
        int limit = jsInt(rawLimit, 10);
        // FAITHFUL(node-quirk): utils/classes/explore.js:423 computes skip before parseInt; numeric strings coerce the same.
        return new Page(page, limit, (page - 1) * limit);
    }

    private Date date(Object value) {
        if (value == null) return null;
        if (value instanceof Date date) return date;
        if (value instanceof Number number) return new Date(number.longValue());
        String text = String.valueOf(value);
        try { return Date.from(Instant.parse(text)); }
        catch (Exception ignored) { return new Date(text); }
    }

    private boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        String text = value == null ? "" : String.valueOf(value).trim();
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) return false;
        return !text.isEmpty();
    }

    private int jsInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        Matcher matcher = LEADING_INT.matcher(String.valueOf(value).trim());
        return matcher.find() ? Integer.parseInt(matcher.group()) : fallback;
    }

    private boolean present(Object value) {
        return value != null && !String.valueOf(value).isEmpty();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }

    private static final class Page {
        final int page;
        final int limit;
        final int skip;

        Page(int page, int limit, int skip) {
            this.page = page;
            this.limit = limit;
            this.skip = skip;
        }
    }
}
