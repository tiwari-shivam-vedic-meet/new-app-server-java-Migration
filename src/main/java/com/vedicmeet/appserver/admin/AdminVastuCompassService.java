package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminVastuCompassService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");
    private static final List<String> ZONES = List.of("red", "green", "yellow");
    // FAITHFUL(node-quirk): Mongoose strict mode persists only these schema paths (utils/models/vastu-compass-*.js).
    private static final List<String> CATEGORY_FIELDS = List.of("category", "title", "position", "type", "image", "status");
    private static final List<String> ZONE_FIELDS = List.of("vastuCategoryId", "zone", "descriptionEngish", "descriptionHindi", "file", "status");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final MediaUploadService uploads;
    private final CacheService cache;
    private final AppConstants constants;

    public AdminVastuCompassService(MongoTemplate mongo, AdminMongoSupport support,
                                    MediaUploadService uploads, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.uploads = uploads;
        this.cache = cache;
        this.constants = constants;
    }

    public Map<String, Object> listCategory(Map<String, String> query) {
        query = query == null ? Map.of() : query;
        int limit = jsInt(query.get("limit"), 10);
        int page = jsInt(query.get("page"), 1);
        int skipIndex = (page - 1) * limit;
        String search = query.getOrDefault("search", "");

        Document params = new Document();
        if (!search.isEmpty()) {
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).find(params)
                .sort(new Document("createdAt", -1)).skip(skipIndex).limit(limit).into(new ArrayList<>());
        list.replaceAll(this::withCategoryVirtual);
        long total = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> addVastuCompassCategory(Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        require(body, "category");
        require(body, "title");

        Document titleExist = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES)
                .find(new Document("title", body.get("title"))).first();
        if (titleExist != null) throw new IllegalStateException("TITLE_EXIST");

        // FAITHFUL(node-quirk): Mongoose create(input) keeps only schema paths and drops the rest (vastu-compass.js:50).
        Document input = pick(body, CATEGORY_FIELDS);
        Document master = mongo.getCollection(Collections.MASTERS).find()
                .projection(new Document("_id", 0).append("vastuCategory", 1)).first();
        Object masterCategories = master == null ? null : master.get("vastuCategory");
        if (masterCategories instanceof List<?> categories && !categories.isEmpty()) {
            // FAITHFUL(node-quirk): vastu-compass.js:37-38 unknown categories get JavaScript indexOf's -1 position.
            input.put("position", categories.indexOf(body.get("category")));
        }

        Object image = firstPresent(body, "vastuCompassCategoryImage", "image");
        if (isBlank(image)) throw new IllegalArgumentException("IMAGE_REQUIRE");
        input.put("image", uploadedValue(image));

        Date now = new Date();
        input.put("_id", new ObjectId());
        // FAITHFUL(node-quirk): Mongoose applies schema defaults for keys the client omits.
        input.putIfAbsent("category", "");
        input.putIfAbsent("title", "");
        input.putIfAbsent("type", "");
        input.putIfAbsent("status", true);
        input.put("createdAt", now);
        input.put("updatedAt", now);
        mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).insertOne(input);
        cache.invalidate("vastu:category:list");
        return withCategoryVirtual(input);
    }

    public Map<String, Object> updateVastuCategory(Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        require(body, "vastuCategoryId");

        Object id = support.id(body.get("vastuCategoryId"));
        Document categoryExist = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES)
                .find(new Document("_id", id)).first();
        if (categoryExist == null) throw new IllegalStateException("VASTU_CATEGORY_NOT_EXIST");

        Document titleExist = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).find(
                new Document("_id", new Document("$nin", List.of(id))).append("title", body.get("title"))).first();
        if (titleExist != null) throw new IllegalStateException("TITLE_EXIST");

        // FAITHFUL(node-quirk): strict-mode $set keeps only schema paths, so vastuCategoryId is not persisted (vastu-compass.js:86).
        Document set = pick(body, CATEGORY_FIELDS);
        Object image = firstPresent(body, "vastuCompassCategoryImage", "image");
        if (!isBlank(image)) set.put("image", uploadedValue(image));
        set.put("updatedAt", new Date());

        Document result = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).findOneAndUpdate(
                new Document("_id", id), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("vastu:category:list");
        return withCategoryVirtual(result);
    }

    public Map<String, Object> blockUnblockCategory(Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        require(body, "vastuCategoryId");
        requireBoolean(body, "status");

        Object id = support.id(body.get("vastuCategoryId"));
        Document categoryExist = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES)
                .find(new Document("_id", id)).first();
        if (categoryExist == null) throw new IllegalStateException("VASTU_CATEGORY_NOT_EXIST");

        Document result = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).findOneAndUpdate(
                new Document("_id", id),
                new Document("$set", new Document("status", body.get("status")).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("vastu:category:list");
        return withCategoryVirtual(result);
    }

    public Map<String, Object> addEditZone(Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        require(body, "vastuCategoryId");
        requireZone(body);

        Object categoryId = support.id(body.get("vastuCategoryId"));
        Document categoryExist = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES)
                .find(new Document("_id", categoryId)).first();
        if (categoryExist == null) throw new IllegalStateException("VASTU_CATEGORY_NOT_EXIST");

        // FAITHFUL(node-quirk): Mongoose keeps only schema paths; vastuCompassZoneFile (virtual) is dropped (vastu-compass.js:168-187).
        Document input = pick(body, ZONE_FIELDS);
        input.put("vastuCategoryId", categoryId);
        Object file = firstPresent(body, "vastuCompassZoneFile", "file");
        if (!isBlank(file)) input.put("file", uploadedValue(file));

        Document vastuZoneExist = mongo.getCollection(Collections.VASTU_COMPASS_ZONES).find(
                new Document("vastuCategoryId", categoryId).append("zone", body.get("zone"))).first();
        Document result;
        Date now = new Date();
        if (vastuZoneExist != null) {
            input.put("updatedAt", now);
            result = mongo.getCollection(Collections.VASTU_COMPASS_ZONES).findOneAndUpdate(
                    new Document("_id", vastuZoneExist.get("_id")), new Document("$set", input),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        } else {
            input.put("_id", new ObjectId());
            // FAITHFUL(node-quirk): Mongoose applies schema defaults on create for omitted keys.
            input.putIfAbsent("descriptionEngish", "");
            input.putIfAbsent("descriptionHindi", "");
            input.putIfAbsent("file", "");
            input.putIfAbsent("status", false);
            input.put("createdAt", now);
            input.put("updatedAt", now);
            mongo.getCollection(Collections.VASTU_COMPASS_ZONES).insertOne(input);
            result = input;
        }
        return withZoneVirtual(result);
    }

    public Map<String, Object> getZone(Map<String, String> query) {
        query = query == null ? Map.of() : query;
        require(query, "vastuCategoryId");
        requireZone(query);

        Object categoryId = support.id(query.get("vastuCategoryId"));
        Document categoryExist = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES)
                .find(new Document("_id", categoryId)).first();
        if (categoryExist == null) throw new IllegalStateException("VASTU_CATEGORY_NOT_EXIST");

        List<Document> pipeline = List.of(
                Document.parse("{ \"$match\": { \"zone\": " + json(query.get("zone")) + ", \"vastuCategoryId\": "
                        + objectIdJson(categoryId) + " } }"),
                Document.parse("{ \"$lookup\": { \"from\": " + json(Collections.VASTU_COMPASS_CATEGORIES)
                        + ", \"let\": { \"vastuCategoryId\": \"$vastuCategoryId\" }, \"pipeline\": [ { \"$match\": { \"$expr\": { \"$eq\": [\"$$vastuCategoryId\", \"$_id\"] } } }, { \"$project\": { \"_id\": 0, \"category\": 1, \"title\": 1, \"vastuCompassCategoryImage\": { \"$cond\": [{ \"$eq\": [\"$image\", \"\"] }, \"\", { \"$concat\": ["
                        + json(constants.mediaUrl) + ", \"$image\"] }] } } } ], \"as\": \"vastuCategory\" } }"),
                Document.parse("{ \"$unwind\": \"$vastuCategory\" }"));
        List<Document> list = mongo.getCollection(Collections.VASTU_COMPASS_ZONES)
                .aggregate(pipeline).into(new ArrayList<>());

        // FAITHFUL(node-quirk): vastu-compass.js:154-158 returns a fresh empty object when the aggregate returns no rows.
        if (list != null && !list.isEmpty()) return list.get(0);
        return new LinkedHashMap<>();
    }

    private Document withCategoryVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = str(source.get("image"));
        result.put("vastuCompassCategoryImage", image.isBlank() ? "" : constants.mediaUrl + image);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Document withZoneVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String file = str(source.get("file"));
        result.put("vastuCompassZoneFile", file.isBlank() ? "" : constants.mediaUrl + file);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private int jsInt(String raw, int fallback) {
        if (raw == null) return fallback;
        Matcher matcher = LEADING_INT.matcher(raw.trim());
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private void require(Map<?, ?> input, String key) {
        if (input == null || isBlank(input.get(key))) throw new IllegalArgumentException("\"" + key + "\" is required");
    }

    private void requireBoolean(Map<?, ?> input, String key) {
        if (input == null || !input.containsKey(key) || input.get(key) == null)
            throw new IllegalArgumentException("\"" + key + "\" is required");
        if (!(input.get(key) instanceof Boolean)) throw new IllegalArgumentException("\"" + key + "\" must be a boolean");
    }

    private void requireZone(Map<?, ?> input) {
        require(input, "zone");
        if (!ZONES.contains(str(input.get("zone"))))
            throw new IllegalArgumentException("\"zone\" must be one of [red, green, yellow]");
    }

    private Document pick(Map<String, Object> body, List<String> fields) {
        // FAITHFUL(node-quirk): replicate Mongoose strict mode by keeping only schema paths present in the input.
        Document out = new Document();
        for (String field : fields) {
            if (body.containsKey(field)) out.put(field, body.get(field));
        }
        return out;
    }

    private Object firstPresent(Map<String, Object> input, String first, String second) {
        Object value = input.get(first);
        return isBlank(value) ? input.get(second) : value;
    }

    private String uploadedValue(Object value) {
        if (value instanceof MultipartFile file) return uploads.upload(file, "vastu");
        return String.valueOf(value);
    }

    private boolean isBlank(Object value) {
        if (value instanceof MultipartFile file) return file.isEmpty();
        return value == null || String.valueOf(value).isBlank();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String json(String value) {
        return "\"" + str(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String objectIdJson(Object id) {
        if (id instanceof ObjectId objectId) return "{ \"$oid\": \"" + objectId.toHexString() + "\" }";
        return json(str(id));
    }
}
