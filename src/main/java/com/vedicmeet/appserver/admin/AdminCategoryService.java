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

/**
 * Faithful native-driver port of the Node admin Category module
 * (rest-apis/modules/admin/category.js + utils/classes/category.js).
 *
 * The Node service returns Mongoose docs with {@code toJSON:{virtuals:true}}, so created/updated
 * docs expose the {@code categoryImage}/{@code categoryMedia} virtual AND the default {@code id}
 * virtual; both are reproduced by {@link #withCategoryVirtual}/{@link #withMediaVirtual}.
 *
 * FAITHFUL notes carried from Node:
 *  - void routes (updateCategory, blockUnblock) return {@code undefined} in Node, so their HTTP body
 *    omits {@code result}; here they return {@code null} and the envelope renders {@code result:null}
 *    (systemic null-vs-absent gap, accepted across the migration).
 *  - Joi validation is NOT ported (systemic). Where a Node route's Joi schema would reject a field
 *    (e.g. {@code listSchema} forbids {@code status}), the service logic is still ported verbatim and
 *    the gap is annotated inline.
 *  - MediaUploadService has no {@code oldFile}-delete overload, so updateMusicOrMantra uploads the new
 *    object without deleting the previous one (same divergence as the gallery port).
 */
@Service
public class AdminCategoryService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final MediaUploadService uploads;
    private final CacheService cache;
    private final AppConstants constants;

    public AdminCategoryService(MongoTemplate mongo, AdminMongoSupport support,
                                MediaUploadService uploads, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.uploads = uploads;
        this.cache = cache;
        this.constants = constants;
    }

    // ---------------------------------------------------------------- reads

    public Object listCategory(Map<String, String> query) {
        int limit = jsInt(query.get("limit"), 10);
        int page = jsInt(query.get("page"), 1);
        int skipIndex = (page - 1) * limit;
        String search = query.getOrDefault("search", "");

        Document params = new Document();
        // FAITHFUL(validation-gap): Node listSchema (Joi, allowUnknown:false) rejects `status`, so this
        // branch is unreachable via the Node HTTP route; ported verbatim since Joi is not ported here.
        boolean statusSet = query.get("status") != null;
        boolean statusValue = false;
        if (statusSet) {
            statusValue = "true".equalsIgnoreCase(query.get("status")); // string -> boolean, else false
            params.append("status", statusValue);
        }
        if (!search.isEmpty()) {
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        // FAITHFUL(node-quirk): `params.status || 'all'` -> status:false collapses to 'all' (key collides
        // with the unfiltered listing); only status:true yields a distinct cache key.
        String cacheKey = !search.isEmpty() ? null
                : "category:list:" + ((statusSet && statusValue) ? "true" : "all") + ":" + page + ":" + limit;
        if (cacheKey != null) {
            Document cached = cache.get(cacheKey, Document.class);
            if (cached != null) return cached;
        }

        List<Document> list = mongo.getCollection(Collections.CATEGORIES)
                .aggregate(listCategoryPipeline(params, skipIndex, limit)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.CATEGORIES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        if (cacheKey != null) cache.set(cacheKey, result, 60L * 60L);
        return result;
    }

    /** {@code [$match,$project,$sort,$skip,$limit]} for the category listing. */
    List<Document> listCategoryPipeline(Document params, int skipIndex, int limit) {
        return List.of(
                new Document("$match", params),
                new Document("$project", new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("categoryImage", concatIf("$image"))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    public Object listMediaCategory(Map<String, String> query) {
        int limit = jsInt(query.get("limit"), 10);
        int page = jsInt(query.get("page"), 1);
        int skipIndex = (page - 1) * limit;
        String search = query.getOrDefault("search", "");

        Document params = new Document("categoryId", objectIdForMatch(query.get("categoryId")));
        if (!search.isEmpty()) {
            params.append("description", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = mongo.getCollection(Collections.CATEGORIES_MUSIC)
                .aggregate(listMediaCategoryPipeline(params, skipIndex, limit)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.CATEGORIES_MUSIC).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    /** {@code [$match,$lookup,$unwind,$project,$sort,$skip,$limit]} for the category-media listing. */
    List<Document> listMediaCategoryPipeline(Document params, int skipIndex, int limit) {
        Document lookup = new Document("from", Collections.CATEGORIES)
                .append("let", new Document("categoryId", "$categoryId"))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$eq", List.of("$$categoryId", "$_id")))),
                        new Document("$project", new Document("title", 1).append("status", 1).append("createdAt", 1)
                                .append("categoryImage", concatIf("$image")))))
                .append("as", "categoriesDetails");
        return List.of(
                new Document("$match", params),
                new Document("$lookup", lookup),
                new Document("$unwind", "$categoriesDetails"),
                new Document("$project", new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("categoryMedia", concatIf("$music"))
                        .append("categoriesDetails", 1).append("description", 1).append("type", 1)),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    // --------------------------------------------------------------- writes

    public Object addCategory(Map<String, String> body, MultipartFile categoryImage) {
        Document titleExist = mongo.getCollection(Collections.CATEGORIES)
                .find(new Document("title", body.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        if (categoryImage == null || categoryImage.isEmpty()) throw new IllegalArgumentException("IMAGE_REQUIRE");
        String image = uploads.upload(categoryImage, "category");

        Date now = new Date();
        // Mongoose strict schema (categories) persists only {title, image, status} + timestamps.
        Document doc = new Document("_id", new ObjectId())
                .append("title", str(body.get("title")))
                .append("image", image)
                .append("status", true) // schema default
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.CATEGORIES).insertOne(doc);

        cache.invalidate("category:list:*");
        cache.invalidate("master:data:*");
        return withCategoryVirtual(doc);
    }

    public Object updateCategory(Map<String, String> body, MultipartFile categoryImage) {
        Document prev = mongo.getCollection(Collections.CATEGORIES)
                .find(new Document("_id", support.id(body.get("toUpdateId")))).first();
        // FAITHFUL(node-bug): missing record throws 'TITLE_EXIST' (Node's literal, wrong message).
        if (prev == null) throw new IllegalArgumentException("TITLE_EXIST");

        Document set = new Document();
        if (str(body.get("title")).length() > 0) set.append("title", body.get("title")); // if(input?.title)
        if (categoryImage != null && !categoryImage.isEmpty()) set.append("image", uploads.upload(categoryImage, "category"));
        set.append("updatedAt", new Date()); // Mongoose timestamps bump updatedAt on findByIdAndUpdate

        mongo.getCollection(Collections.CATEGORIES).findOneAndUpdate(
                new Document("_id", support.id(body.get("toUpdateId"))), new Document("$set", set));

        cache.invalidate("category:list:*");
        cache.invalidate("master:data:*");
        return null; // Node returns undefined
    }

    public Object blockUnblock(Map<String, Object> body) {
        String type = str(body.get("type"));
        switch (type) {
            case "category" -> {
                Document exist = mongo.getCollection(Collections.CATEGORIES)
                        .find(new Document("_id", support.id(body.get("categoryId")))).first();
                if (exist == null) throw new IllegalStateException("CATEGORY_NOT_EXIST");
                mongo.getCollection(Collections.CATEGORIES).findOneAndUpdate(
                        new Document("_id", support.id(body.get("categoryId"))),
                        new Document("$set", new Document("status", toBool(body.get("status"))).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                cache.invalidate("category:list:*");
                cache.invalidate("master:data:*");
            }
            case "media" -> {
                Document exist = mongo.getCollection(Collections.CATEGORIES_MUSIC)
                        .find(new Document("_id", support.id(body.get("categoryMediaId")))).first();
                if (exist == null) throw new IllegalStateException("CATEGORY_MEDIA_NOT_EXIST");
                mongo.getCollection(Collections.CATEGORIES_MUSIC).findOneAndUpdate(
                        new Document("_id", support.id(body.get("categoryMediaId"))),
                        new Document("$set", new Document("status", toBool(body.get("status"))).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            }
            default -> { /* no-op, mirrors Node default branch */ }
        }
        return null; // Node returns undefined
    }

    public Object addMusicOrMantra(Map<String, String> body, MultipartFile categoryMedia) {
        Document catExist = mongo.getCollection(Collections.CATEGORIES)
                .find(new Document("_id", support.id(body.get("categoryId")))).first();
        if (catExist == null) throw new IllegalStateException("CATEGORY_NOT_EXIST");

        if (categoryMedia == null || categoryMedia.isEmpty()) throw new IllegalArgumentException("MEDIA_REQUIRE");
        String music = uploads.upload(categoryMedia, "category");

        Date now = new Date();
        // Mongoose strict schema (categories_music) persists only {categoryId, description, type, music, status}.
        Document doc = new Document("_id", new ObjectId())
                .append("categoryId", new ObjectId(str(body.get("categoryId")))) // schema ref -> ObjectId cast
                .append("description", str(body.getOrDefault("description", "")))
                .append("type", str(body.getOrDefault("type", "")))
                .append("music", music)
                .append("status", true) // schema default
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.CATEGORIES_MUSIC).insertOne(doc);
        return withMediaVirtual(doc);
    }

    public Object updateMusicOrMantra(Map<String, String> body, MultipartFile categoryMedia) {
        Document exist = mongo.getCollection(Collections.CATEGORIES_MUSIC)
                .find(new Document("_id", support.id(body.get("categoryMediaId")))).first();
        if (exist == null) throw new IllegalStateException("CATEGORY_MEDIA_NOT_EXIST");

        // Node: $set = whole body; Mongoose strict keeps only schema paths of categories_music.
        Document set = new Document();
        if (body.containsKey("categoryId")) set.append("categoryId", new ObjectId(str(body.get("categoryId"))));
        if (body.containsKey("description")) set.append("description", str(body.get("description")));
        if (body.containsKey("type")) set.append("type", str(body.get("type")));
        if (body.containsKey("status")) set.append("status", toBool(body.get("status")));
        if (categoryMedia != null && !categoryMedia.isEmpty()) set.append("music", uploads.upload(categoryMedia, "category"));
        set.append("updatedAt", new Date()); // Mongoose timestamps

        Document updated = mongo.getCollection(Collections.CATEGORIES_MUSIC).findOneAndUpdate(
                new Document("_id", support.id(body.get("categoryMediaId"))), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withMediaVirtual(updated);
    }

    // --------------------------------------------------------------- helpers

    /** {@code {$cond:{if:{$ne:[field,""]}, then:{$concat:[MEDIA_URL, field]}, else:""}}}. */
    private Document concatIf(String field) {
        return new Document("$cond", new Document("if", new Document("$ne", List.of(field, "")))
                .append("then", new Document("$concat", List.of(constants.mediaUrl, field)))
                .append("else", ""));
    }

    private Document withCategoryVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = str(source.get("image"));
        result.put("categoryImage", image.isEmpty() ? "" : constants.mediaUrl + image);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Document withMediaVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String music = str(source.get("music"));
        result.put("categoryMedia", music.isEmpty() ? "" : constants.mediaUrl + music);
        result.put("id", str(source.get("_id")));
        return result;
    }

    /** Mirrors {@code new ObjectId(input.categoryId)}: null -> fresh id; invalid -> throw; valid -> that id. */
    private Object objectIdForMatch(String raw) {
        if (raw == null) return new ObjectId();
        if (ObjectId.isValid(raw)) return new ObjectId(raw);
        throw new IllegalArgumentException("input must be a 24 character hex string, 12 byte Uint8Array, or an integer");
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** JS parseInt with a destructuring default: default only when the key is absent (null). */
    private static int jsInt(String value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(value.trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }
}
