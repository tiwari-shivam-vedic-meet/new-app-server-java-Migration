package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminGrowthTrainingService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final CacheService cache;
    private final PushNotificationService push;
    @SuppressWarnings("unused")
    private final AppConstants constants;

    public AdminGrowthTrainingService(MongoTemplate mongo, AdminMongoSupport support, CacheService cache,
                                      PushNotificationService push, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.cache = cache;
        this.push = push;
        this.constants = constants;
    }

    public Object addGrowthTrainingCategory(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:20 duplicate-title query runs even when title is absent.
        Document titleExist = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        Document doc = categoryCreate(input);
        mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).insertOne(doc);
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:27 invalidates only growth category-list cache.
        cache.invalidate("growth:category:list:*");
        return withIdVirtual(doc);
    }

    public Object updateGrowthTrainingCategory(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("growthTrainingCategoryId"));
        Document exist = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("GROWTH_CATEGORY_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:52 duplicate-title query runs even when title is absent.
        Document duplicate = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)
                .find(new Document("_id", new Document("$nin", List.of(id))).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:56 uses $set: input; Mongoose strict schema drops id/unknown keys.
        Document updated = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).findOneAndUpdate(
                new Document("_id", id), new Document("$set", categorySet(input)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:58 invalidates only growth category-list cache.
        cache.invalidate("growth:category:list:*");
        return withIdVirtual(updated);
    }

    public Object blockUnblockCategory(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("growthTrainingCategoryId"));
        Document exist = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("GROWTH_CATEGORY_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:82 updates category status, then all child media statuses.
        mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).findOneAndUpdate(
                new Document("_id", id),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).updateMany(
                new Document("growthTrainingCategoryId", id),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())));
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:86 returns undefined after updates and cache invalidation.
        cache.invalidate("growth:category:list:*");
        return null;
    }

    public Object listMedidationCategory(Map<String, String> query) {
        Page page = page(query);
        String search = query == null ? "" : query.getOrDefault("search", "");
        Document params = new Document();
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/growth-training.js:113 builds unescaped regex from raw search.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)
                .aggregate(listMedidationCategoryPipeline(params, page.skip, page.limit)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES).countDocuments(params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    /** {@code [$match,$lookup,$project,$sort,$skip,$limit]} for admin growth-training category list. */
    List<Document> listMedidationCategoryPipeline(Document params, int skipIndex, int limit) {
        return List.of(
                new Document("$match", params),
                new Document("$lookup", new Document("from", Collections.GROWTH_TRAINING_MEDIA)
                        .append("let", new Document("growthTrainingCategoryId", "$_id"))
                        .append("pipeline", List.of(new Document("$match", new Document("$expr",
                                new Document("$eq", List.of("$growthTrainingCategoryId", "$$growthTrainingCategoryId"))))))
                        .append("as", "growthTrainingMedia")),
                // FAITHFUL(node-quirk): utils/classes/growth-training.js:135 projects the count under meditationMedia despite growthTrainingMedia lookup.
                new Document("$project", new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("meditationMedia", new Document("$size", "$growthTrainingMedia"))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    public Object getDetailsCategory(Map<String, String> query) {
        Document doc = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)
                .find(new Document("_id", support.id(query == null ? null : query.get("growthTrainingCategoryId")))).first();
        if (doc == null) throw new IllegalStateException("GROWTH_CATEGORY_NOT_EXIST");
        return withIdVirtual(doc);
    }

    public Object addMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document category = mongo.getCollection(Collections.GROWTH_TRAINING_CATEGORIES)
                .find(new Document("_id", support.id(input.get("growthTrainingCategoryId")))).first();
        if (category == null) throw new IllegalStateException("GROWTH_CATEGORY_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:199 duplicate-title query runs even when title is absent.
        Document titleExist = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        Document doc = mediaCreate(input);
        mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).insertOne(doc);
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:204 broadcasts training-session notification after media create; errors are swallowed by firebase.js.
        broadcastTrainingSessionToConsultants(input.get("title"));
        return withIdVirtual(doc);
    }

    public Object editMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("growthTrainingMediaId"));
        Document exist = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("GROWTH_MEDIA_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:230 duplicate-title query runs even when title is absent.
        Document duplicate = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA)
                .find(new Document("_id", new Document("$nin", List.of(id))).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:235 uses $set: input; Mongoose strict schema drops id/unknown keys.
        Document updated = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).findOneAndUpdate(
                new Document("_id", id), new Document("$set", mediaSet(input)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withIdVirtual(updated);
    }

    public Object blockUnblockMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("growthTrainingMediaId"));
        Document exist = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).find(new Document("_id", id)).first();
        if (exist == null) throw new IllegalStateException("GROWTH_MEDIA_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:260 updates only media status and does not invalidate cache.
        Document updated = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).findOneAndUpdate(
                new Document("_id", id),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withIdVirtual(updated);
    }

    public Object detailMedia(Map<String, String> query) {
        Document doc = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA)
                .find(new Document("_id", support.id(query == null ? null : query.get("growthTrainingMediaId")))).first();
        if (doc == null) throw new IllegalStateException("GROWTH_MEDIA_NOT_EXIST");
        return withIdVirtual(doc);
    }

    public Object listMedia(Map<String, String> query) {
        Page page = page(query);
        String categoryId = query == null ? null : query.get("growthTrainingCategoryId");
        Document params = new Document();
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:315 trims category id and throws LIST_TYPE_ERROR unless it is a valid ObjectId.
        if (categoryId != null && ObjectId.isValid(categoryId.trim())) {
            params.append("growthTrainingCategoryId", new ObjectId(categoryId.trim()));
        } else {
            throw new IllegalArgumentException("LIST_TYPE_ERROR");
        }
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/growth-training.js:321 builds unescaped regex from raw search.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        // FAITHFUL(node-quirk): utils/classes/growth-training.js:325 listMedia uses find().sort().skip().limit(), not aggregation.
        FindIterable<Document> iterable = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).find(params)
                .sort(new Document("createdAt", -1)).skip(page.skip).limit(page.limit);
        List<Document> list = iterable.into(new ArrayList<>()).stream().map(this::withIdVirtual).toList();
        long total = mongo.getCollection(Collections.GROWTH_TRAINING_MEDIA).countDocuments(params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private Document categoryCreate(Map<String, Object> input) {
        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/growth-training-category-model.js:5 Mongoose create(input) keeps schema paths and defaults.
        return new Document("_id", new ObjectId()).append("title", str(input.get("title")))
                .append("status", true).append("createdAt", now).append("updatedAt", now);
    }

    private Document categorySet(Map<String, Object> input) {
        Document set = new Document();
        if (input.containsKey("title")) set.append("title", str(input.get("title")));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        set.append("updatedAt", new Date());
        return set;
    }

    private Document mediaCreate(Map<String, Object> input) {
        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/growth-training-media-model.js:6 Mongoose create(input) keeps schema paths and defaults.
        return new Document("_id", new ObjectId())
                .append("growthTrainingCategoryId", support.id(input.get("growthTrainingCategoryId")))
                .append("title", str(input.get("title"))).append("videoLink", str(input.get("videoLink")))
                .append("status", true).append("createdAt", now).append("updatedAt", now);
    }

    private Document mediaSet(Map<String, Object> input) {
        Document set = new Document();
        if (input.containsKey("growthTrainingCategoryId")) set.append("growthTrainingCategoryId", support.id(input.get("growthTrainingCategoryId")));
        if (input.containsKey("title")) set.append("title", str(input.get("title")));
        if (input.containsKey("videoLink")) set.append("videoLink", str(input.get("videoLink")));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        set.append("updatedAt", new Date());
        return set;
    }

    private Document withIdVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private void broadcastTrainingSessionToConsultants(Object sessionTitle) {
        try {
            String title = "New Training Session";
            String body = "A new session \"" + str(sessionTitle) + "\" is now available.";
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("screen", "GrowthAndTraining");
            data.put("sessionTitle", str(sessionTitle));
            // FAITHFUL(node-quirk): utils/functions/firebase.js:390 repeated `device` keys collapse to only {device: {$ne: {fcmToken: []}}}.
            Document filter = new Document("isDeleted", false)
                    .append("device", new Document("$ne", new Document("fcmToken", new ArrayList<>())));
            List<Document> consultants = mongo.getCollection(Collections.CONSULTANTS).find(filter).into(new ArrayList<>());
            for (Document consultant : consultants) {
                for (Object token : fcmTokens(consultant)) {
                    push.sendNotificationAndCons("consultant", str(token), body, data, title, "");
                }
                Date now = new Date();
                mongo.getCollection(Collections.NOTIFICATIONS).insertOne(new Document("_id", new ObjectId())
                        .append("receiverId", consultant.get("_id"))
                        .append("message", body)
                        .append("title", title)
                        .append("userType", "cons")
                        .append("senderType", "system")
                        .append("type", "other")
                        .append("data", data)
                        .append("isRead", false)
                        .append("status", true)
                        .append("createdAt", now).append("updatedAt", now));
            }
        } catch (Exception ignored) {
            // FAITHFUL(node-quirk): utils/functions/firebase.js:414 sendAllConsultantNotification catches and logs, never rethrows.
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> fcmTokens(Document user) {
        Object device = user.get("device");
        if (!(device instanceof Map<?, ?> map)) return List.of();
        Object tokens = map.get("fcmToken");
        return tokens instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private Page page(Map<String, String> query) {
        String rawPage = query == null ? null : query.get("page");
        String rawLimit = query == null ? null : query.get("limit");
        // FAITHFUL(node-quirk): utils/classes/growth-training.js:104 computes skip before parseInt after default destructuring.
        int page = jsInt(rawPage, 1);
        int limit = jsInt(rawLimit, 10);
        return new Page((page - 1) * limit, limit);
    }

    private static int jsInt(Object value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(String.valueOf(value).trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private record Page(int skip, int limit) {}
}
