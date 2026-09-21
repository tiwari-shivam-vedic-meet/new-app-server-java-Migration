package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
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

@Service
public class AdminGalleryService {

    private static final String GALLERY_TYPE_NOT_MATCH = "Gallery type should be either live or profile";
    private static final String IMAGE_REQUIRE = "Image is require";
    private static final String GALLERY_IMAGE_LENGTH = "Gallery images must be between 3 and 10";
    private static final String MINIMUM_THREE_IMAGE_REQUIRED = "For approve minimum three images required";

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final MediaUploadService uploads;
    private final CacheService cache;
    private final AppConstants constants;

    public AdminGalleryService(MongoTemplate mongo, AdminMongoSupport support,
                               MediaUploadService uploads, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.uploads = uploads;
        this.cache = cache;
        this.constants = constants;
    }

    public Map<String, Object> listGallery(Map<String, String> query) {
        String galleryType = defaultValue(query == null ? null : query.get("galleryType"), "profile");
        requireGalleryType(galleryType);
        int page = integer(query == null ? null : query.get("page"), 1);
        int limit = integer(query == null ? null : query.get("limit"), 10);
        int skipIndex = (page - 1) * limit;
        List<Document> galleryList = mongo.getCollection(Collections.CONSULTANTS)
                .aggregate(listGalleryPipeline(query == null ? "" : query.get("search"), galleryType, skipIndex, limit))
                .into(new ArrayList<>());

        Document first = galleryList.isEmpty() ? new Document() : galleryList.get(0);
        List<Document> list = documents(first.get("list"));
        List<Document> count = documents(first.get("count"));
        int total = count.isEmpty() ? 0 : number(count.get(0).get("total"));

        for (Document data : list) {
            int approved = 0;
            for (Document subData : documents(data.get("gallery"))) {
                if (Boolean.TRUE.equals(subData.get("isApprove"))) approved++;
            }
            data.put("status", approved >= 3);
        }
        return page(list, total);
    }

    public Map<String, Object> getConsultantGallery(Map<String, String> query) {
        String consultantId = required(query, "consultantId");
        String galleryType = required(query, "galleryType");
        requireGalleryType(galleryType);
        String cacheKey = "gallery:" + consultantId + ":" + galleryType;
        Map<String, Object> cached = cache == null ? null : cache.get(cacheKey, Map.class);
        if (cached != null) return cached;

        Document countFilter = new Document("consultantId", support.id(consultantId))
                .append("galleryType", galleryType).append("isApprove", true);
        long galleryCount = mongo.getCollection(Collections.GALLERIES).countDocuments(countFilter);
        boolean status = galleryCount >= 1;
        // FAITHFUL(node-quirk): details status flips true at >= 1 approved image despite the three-image approval wording elsewhere — utils/classes/gallery.js:251-264.

        List<Document> aggregate = mongo.getCollection(Collections.CONSULTANTS)
                .aggregate(consultantGalleryPipeline(consultantId, galleryType)).into(new ArrayList<>());
        Object list = aggregate.isEmpty() ? List.of() : aggregate.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("status", status);
        if (cache != null) cache.set(cacheKey, result, 30 * 60);
        return result;
    }

    public Void approveGalleryImages(Map<String, Object> body) {
        String consultantId = required(body, "consultantId");
        String galleryType = required(body, "galleryType");
        requireGalleryType(galleryType);
        Object rawImagesId = body == null ? null : body.get("imagesId");
        if (rawImagesId == null) throw new IllegalArgumentException("\"imagesId\" is required");

        Document existing = mongo.getCollection(Collections.GALLERIES).find(new Document("consultantId", support.id(consultantId))
                .append("galleryType", galleryType).append("isApprove", true)).first();
        int imagesLength = imagesLength(rawImagesId);
        if (existing == null && imagesLength < 1) throw new IllegalArgumentException(MINIMUM_THREE_IMAGE_REQUIRED);

        if (rawImagesId instanceof String) {
            // FAITHFUL(node-quirk): Joi allows a string imagesId, but the service then calls imagesId.map(...) and crashes — utils/classes/gallery.js:22-39.
            throw new IllegalArgumentException("imagesId.map is not a function");
        }
        for (Object imageId : imagesIds(rawImagesId)) {
            mongo.getCollection(Collections.GALLERIES).findOneAndUpdate(new Document("_id", support.id(imageId)),
                    new Document("$set", new Document("isApprove", true)), new FindOneAndUpdateOptions());
        }

        List<Document> consultantImage = mongo.getCollection(Collections.GALLERIES).find(new Document("consultantId", support.id(consultantId))
                .append("galleryType", galleryType).append("isApprove", false)).into(new ArrayList<>());
        for (Document data : consultantImage) {
            // FAITHFUL(node-quirk): Node uses async forEach without awaiting deletes; the route still returns undefined — utils/classes/gallery.js:45-47.
            mongo.getCollection(Collections.GALLERIES).findOneAndDelete(new Document("_id", data.get("_id")));
        }
        return null;
    }

    public Document deleteImage(Map<String, String> query) {
        String id = required(query, "id");
        return withGalleryVirtual(mongo.getCollection(Collections.GALLERIES)
                .findOneAndDelete(new Document("_id", support.id(id))));
    }

    public Object addGallery(Map<String, Object> body) {
        return addGallery(body, filesFromBody(body == null ? null : body.get("images")));
    }

    public Object addGallery(Map<String, Object> body, List<MultipartFile> images) {
        String consultantId = required(body, "consultantId");
        String galleryType = required(body, "galleryType");
        requireGalleryType(galleryType);
        if (images == null) throw new IllegalArgumentException(IMAGE_REQUIRE);

        List<Document> galleryExist = mongo.getCollection(Collections.GALLERIES)
                .find(new Document("consultantId", support.id(consultantId))).into(new ArrayList<>());
        if (galleryExist.isEmpty()) {
            // FAITHFUL(node-quirk): impossible && means the 3..10 image length guard never fires — utils/classes/gallery.js:278-282.
            if (images.size() <= 1 && images.size() > 10) throw new IllegalArgumentException(GALLERY_IMAGE_LENGTH);
        }

        if (images.isEmpty()) return null;
        // FAITHFUL(node-quirk): single-file branch returns the created document and skips cache invalidation; array branch invalidates and returns undefined — utils/classes/gallery.js:382-401.
        if (images.size() == 1) return createGallery(body, consultantId, galleryType, images.get(0));
        for (MultipartFile file : images) createGallery(body, consultantId, galleryType, file);
        if (cache != null) {
            cache.invalidate("gallery:" + consultantId + ":*");
            cache.invalidate("gallery:list:" + consultantId + ":*");
        }
        return null;
    }

    List<Document> listGalleryPipeline(String search, String galleryType, int skipIndex, int limit) {
        Document query = new Document();
        if (search != null && !search.isBlank()) {
            query.put("name", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }
        String media = jsonString(constants.mediaUrl);
        String params = new Document("galleryType", galleryType).toJson();
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": " + query.toJson() + " }"));
        pipeline.add(Document.parse("{ \"$lookup\": { \"from\": \"galleries\", \"let\": { \"consultantId\": \"$_id\" }, \"pipeline\": [ { \"$match\": { \"$expr\": { \"$eq\": [\"$consultantId\", \"$$consultantId\"] } } }, { \"$match\": " + params + " }, { \"$project\": { \"isApprove\": 1, \"galleryType\": 1, \"image\": 1, \"imageType\": 1, \"createdAt\": 1, \"profileImage\": { \"$cond\": { \"if\": { \"$ne\": [\"$image\", \"\"] }, \"then\": { \"$concat\": [" + media + ", \"$image\"] }, \"else\": \"\" } } } } ], \"as\": \"gallery\" } }"));
        pipeline.add(Document.parse("{ \"$match\": { \"gallery\": { \"$ne\": [] } } }"));
        pipeline.add(Document.parse("{ \"$addFields\": { \"latestGalleryDate\": { \"$max\": \"$gallery.createdAt\" } } }"));
        pipeline.add(Document.parse("{ \"$project\": { \"consultantId\": \"$_id\", \"name\": 1, \"profileImage\": { \"$cond\": { \"if\": { \"$and\": [ { \"$ne\": [\"$profileImage\", \"\"] }, { \"$not\": [ { \"$regexMatch\": { \"input\": \"$profileImage\", \"regex\": \"^https?://\" } } ] } ] }, \"then\": { \"$concat\": [" + media + ", \"$profileImage\"] }, \"else\": \"$profileImage\" } }, \"gallery\": 1, \"totalImage\": { \"$size\": \"$gallery\" }, \"createdAt\": 1, \"latestGalleryDate\": 1 } }"));
        pipeline.add(Document.parse("{ \"$facet\": { \"list\": [ { \"$sort\": { \"latestGalleryDate\": -1 } }, { \"$skip\": " + skipIndex + " }, { \"$limit\": " + limit + " } ], \"count\": [ { \"$count\": \"total\" } ] } }"));
        return pipeline;
    }

    List<Document> consultantGalleryPipeline(String consultantId, String galleryType) {
        String media = jsonString(constants.mediaUrl);
        String params = new Document("_id", new ObjectId(consultantId)).toJson();
        String query = new Document("galleryType", galleryType).toJson();
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": " + params + " }"));
        pipeline.add(Document.parse("{ \"$lookup\": { \"from\": \"galleries\", \"let\": { \"consultantId\": \"$_id\" }, \"pipeline\": [ { \"$match\": { \"$expr\": { \"$eq\": [\"$consultantId\", \"$$consultantId\"] } } }, { \"$match\": " + query + " }, { \"$project\": { \"isApprove\": 1, \"galleryType\": 1, \"image\": { \"$cond\": { \"if\": { \"$ne\": [\"$image\", \"\"] }, \"then\": { \"$concat\": [" + media + ", \"$image\"] }, \"else\": \"\" } }, \"imageType\": 1, \"createdAt\": 1 } } ], \"as\": \"gallery\" } }"));
        pipeline.add(Document.parse("{ \"$project\": { \"name\": 1, \"profileImageImage\": { \"$cond\": { \"if\": { \"$ne\": [\"$image\", \"\"] }, \"then\": { \"$concat\": [" + media + ", \"$image\"] }, \"else\": \"\" } }, \"profileImage\": { \"$cond\": { \"if\": { \"$and\": [ { \"$ne\": [\"$profileImage\", \"\"] }, { \"$not\": [ { \"$regexMatch\": { \"input\": \"$profileImage\", \"regex\": \"^https?://\" } } ] } ] }, \"then\": { \"$concat\": [" + media + ", \"$profileImage\"] }, \"else\": \"$profileImage\" } }, \"gallery\": 1, \"createdAt\": 1, \"totalImage\": { \"$size\": \"$gallery\" } } }"));
        return pipeline;
    }

    private Document createGallery(Map<String, Object> input, String consultantId, String galleryType, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException(IMAGE_REQUIRE);
        Date now = new Date();
        Document fileInput = new Document();
        if (input != null) fileInput.putAll(input);
        fileInput.put("_id", new ObjectId());
        fileInput.put("consultantId", support.id(consultantId));
        fileInput.put("galleryType", galleryType);
        fileInput.put("isApprove", false);
        fileInput.put("imageType", imageType(file));
        if ("profile".equals(galleryType)) fileInput.put("image", uploads.upload(file, "gallery"));
        // FAITHFUL(node-quirk): live uploads are stored in "video" while list/detail pipelines still project "image" — utils/classes/gallery.js:371-397.
        else fileInput.put("video", uploads.upload(file, "gallery"));
        fileInput.putIfAbsent("status", true);
        fileInput.put("createdAt", now);
        fileInput.put("updatedAt", now);
        mongo.getCollection(Collections.GALLERIES).insertOne(fileInput);
        return withGalleryVirtual(fileInput);
    }

    private Document withGalleryVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = value(source.get("image"));
        result.put("imageUrl", image.isBlank() ? "" : constants.mediaUrl + image);
        result.put("id", value(source.get("_id")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<MultipartFile> filesFromBody(Object raw) {
        if (raw == null) return null;
        if (raw instanceof MultipartFile file) return List.of(file);
        if (raw instanceof List<?> list) return (List<MultipartFile>) list;
        throw new IllegalArgumentException(IMAGE_REQUIRE);
    }

    private List<Object> imagesIds(Object raw) {
        if (raw instanceof List<?> list) return new ArrayList<>(list);
        throw new IllegalArgumentException("imagesId.map is not a function");
    }

    private int imagesLength(Object raw) {
        if (raw instanceof String text) return text.length();
        if (raw instanceof List<?> list) return list.size();
        throw new IllegalArgumentException("imagesId.map is not a function");
    }

    @SuppressWarnings("unchecked")
    private List<Document> documents(Object raw) {
        return raw instanceof List<?> list ? (List<Document>) list : new ArrayList<>();
    }

    private Map<String, Object> page(List<Document> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private void requireGalleryType(String galleryType) {
        if (!"profile".equals(galleryType) && !"live".equals(galleryType)) throw new IllegalArgumentException(GALLERY_TYPE_NOT_MATCH);
    }

    private String required(Map<?, ?> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("\"" + key + "\" is required");
        return String.valueOf(value);
    }

    private int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (Exception ignored) { return fallback; }
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    private String imageType(MultipartFile file) {
        String type = value(file.getContentType());
        int slash = type.indexOf('/');
        return slash < 0 ? type : type.substring(0, slash);
    }

    private String defaultValue(String value, String fallback) { return value == null ? fallback : value; }
    private String value(Object raw) { return raw == null ? "" : String.valueOf(raw); }

    private String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '\"') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else out.append(c);
        }
        return out.append('\"').toString();
    }
}
