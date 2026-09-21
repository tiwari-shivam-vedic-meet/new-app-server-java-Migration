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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminMusicService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final MediaUploadService uploads;
    private final CacheService cache;
    @SuppressWarnings("unused")
    private final CleverTapClient cleverTap;
    private final AppConstants constants;

    public AdminMusicService(MongoTemplate mongo, AdminMongoSupport support, MediaUploadService uploads,
                             CacheService cache, CleverTapClient cleverTap, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.uploads = uploads;
        this.cache = cache;
        this.cleverTap = cleverTap;
        this.constants = constants;
    }

    public Object addMusicCategory(Map<String, String> body, MultipartFile musicImage) {
        Map<String, String> input = form(body);
        Document titleExist = mongo.getCollection(Collections.MUSICS)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/music.js:22-ish requires files.musicImage after title check.
        if (musicImage == null || musicImage.isEmpty()) throw new IllegalArgumentException("IMAGE_REQUIRE");
        // FAITHFUL(node-quirk): utils/classes/music.js:24-ish uploads category image with type 'music'.
        String image = uploads.upload(musicImage, "music");

        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/music-model.js:6-ish Mongoose create(input) strips non-schema keys and applies defaults.
        Document doc = new Document("_id", new ObjectId())
                .append("title", input.getOrDefault("title", ""))
                .append("image", image)
                .append("status", true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.MUSICS).insertOne(doc);

        // FAITHFUL(node-quirk): utils/classes/music.js:34-ish invalidates both category-list and master-data patterns.
        cache.invalidate("music:category:list:*");
        cache.invalidate("master:data:*");
        return withMusicId(doc);
    }

    public Object blockMusicCategory(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document exist = mongo.getCollection(Collections.MUSICS)
                .find(new Document("_id", support.id(input.get("musicId")))).first();
        if (exist == null) throw new IllegalStateException("MUSIC_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/music.js:149-ish only status is set from input.
        Document updated = mongo.getCollection(Collections.MUSICS).findOneAndUpdate(
                new Document("_id", support.id(input.get("musicId"))),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        cache.invalidate("music:category:list:*");
        cache.invalidate("master:data:*");
        return withMusicId(updated);
    }

    public Object listMusicCategory(Map<String, String> query) {
        Page page = page(query);
        Document params = new Document();
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/music.js:91-ish builds ".*" + search + ".*" without escaping.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = mongo.getCollection(Collections.MUSICS)
                .aggregate(listMusicCategoryPipeline(params, page.skip, page.limit)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.MUSICS).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    /** {@code [$match,$lookup,$project,$sort,$skip,$limit]} for admin music category list. */
    List<Document> listMusicCategoryPipeline(Document params, int skipIndex, int limit) {
        Document lookup = new Document("from", Collections.MUSIC_MEDIA)
                .append("let", new Document("musicId", "$_id"))
                .append("pipeline", List.of(new Document("$match", new Document("$expr",
                        new Document("$eq", List.of("$musicId", "$$musicId"))))))
                .append("as", "musicMedia");
        return List.of(
                new Document("$match", params),
                new Document("$lookup", lookup),
                new Document("$project", new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("musicImage", media("$image"))
                        .append("musicMedia", new Document("$size", "$musicMedia"))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    public Object getDetailMusicCategory(Map<String, String> query) {
        Document music = mongo.getCollection(Collections.MUSICS)
                .find(new Document("_id", support.id(query == null ? null : query.get("musicId")))).first();
        if (music == null) throw new IllegalStateException("MUSIC_NOT_EXIST");
        return withMusicId(music);
    }

    public Object addMusicMedia(Map<String, String> body, MultipartFile musicImage, MultipartFile musicMedia) {
        Map<String, String> input = form(body);
        Document category = mongo.getCollection(Collections.MUSICS)
                .find(new Document("_id", support.id(input.get("musicId")))).first();
        if (category == null) throw new IllegalStateException("MUSIC_NOT_EXIST");

        Document titleExist = mongo.getCollection(Collections.MUSIC_MEDIA)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/music.js:234-ish requires files.musicImage before files.musicMedia.
        if (musicImage == null || musicImage.isEmpty()) throw new IllegalArgumentException("IMAGE_REQUIRE");
        String image = uploads.upload(musicImage, "music");
        // FAITHFUL(node-quirk): utils/classes/music.js:245-ish requires media file and uses the same upload type 'music'.
        if (musicMedia == null || musicMedia.isEmpty()) throw new IllegalArgumentException("MEDIA_REQUIRE");
        String music = uploads.upload(musicMedia, "music");

        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/music-media-model.js:7-ish create(input) keeps only schema paths/defaults.
        Document doc = new Document("_id", new ObjectId())
                .append("musicId", support.id(input.get("musicId")))
                .append("title", input.getOrDefault("title", ""))
                .append("musicDuration", input.getOrDefault("musicDuration", ""))
                .append("music", music)
                .append("image", image)
                .append("status", true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.MUSIC_MEDIA).insertOne(doc);
        return withMusicMediaVirtuals(doc);
    }

    public Object editMusicMedia(Map<String, String> body, MultipartFile musicImage, MultipartFile musicMedia) {
        Map<String, String> input = form(body);
        Document exist = mongo.getCollection(Collections.MUSIC_MEDIA)
                .find(new Document("_id", support.id(input.get("musicMediaId")))).first();
        if (exist == null) throw new IllegalStateException("MUSIC_MEDIA_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/music.js:275-ish duplicate-title query runs even when title is absent.
        Document duplicate = mongo.getCollection(Collections.MUSIC_MEDIA).find(new Document("_id",
                new Document("$nin", List.of(support.id(input.get("musicMediaId")))))
                .append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/music.js:291-ish $set: input is strict-cast by the schema.
        Document set = new Document();
        if (input.containsKey("musicId")) set.append("musicId", support.id(input.get("musicId")));
        if (input.containsKey("title")) set.append("title", input.get("title"));
        if (input.containsKey("musicDuration")) set.append("musicDuration", input.get("musicDuration"));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        if (musicImage != null && !musicImage.isEmpty()) set.append("image", uploads.upload(musicImage, "music"));
        if (musicMedia != null && !musicMedia.isEmpty()) set.append("music", uploads.upload(musicMedia, "music"));
        set.append("updatedAt", new Date());

        Document updated = mongo.getCollection(Collections.MUSIC_MEDIA).findOneAndUpdate(
                new Document("_id", support.id(input.get("musicMediaId"))), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withMusicMediaVirtuals(updated);
    }

    public Object getDetailMusicMedia(Map<String, String> query) {
        Document media = mongo.getCollection(Collections.MUSIC_MEDIA)
                .find(new Document("_id", support.id(query == null ? null : query.get("musicMediaId")))).first();
        if (media == null) throw new IllegalStateException("MUSIC_MEDIA_NOT_EXIST");
        return withMusicMediaVirtuals(media);
    }

    public Object blockMusicMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document exist = mongo.getCollection(Collections.MUSIC_MEDIA)
                .find(new Document("_id", support.id(input.get("musicMediaId")))).first();
        if (exist == null) throw new IllegalStateException("MUSIC_MEDIA_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/music.js:318-ish media block does not invalidate any cache.
        Document updated = mongo.getCollection(Collections.MUSIC_MEDIA).findOneAndUpdate(
                new Document("_id", support.id(input.get("musicMediaId"))),
                new Document("$set", new Document("status", toBool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withMusicMediaVirtuals(updated);
    }

    public Object listMusicMedia(Map<String, String> query) {
        Page page = page(query);
        Document params = new Document("musicId", support.id(query == null ? null : query.get("musicId")));
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/music.js:385-ish admin media list regex is unescaped.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        // FAITHFUL(node-quirk): utils/classes/music.js:391-ish uses find().sort().skip().limit(), not aggregation.
        FindIterable<Document> iterable = mongo.getCollection(Collections.MUSIC_MEDIA).find(params)
                .sort(new Document("createdAt", -1)).skip(page.skip).limit(page.limit);
        List<Document> list = iterable.into(new ArrayList<>()).stream()
                .map(this::withMusicMediaVirtuals).toList();
        long total = mongo.getCollection(Collections.MUSIC_MEDIA).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private Document media(String field) {
        return new Document("$cond", new Document("if", new Document("$ne", List.of(field, "")))
                .append("then", new Document("$concat", List.of(constants.mediaUrl, field)))
                .append("else", ""));
    }

    private Document withMusicId(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Document withMusicMediaVirtuals(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = str(source.get("image"));
        String music = str(source.get("music"));
        result.put("musicImage", image.isEmpty() ? "" : constants.mediaUrl + image);
        result.put("musicMedia", music.isEmpty() ? "" : constants.mediaUrl + music);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Page page(Map<String, String> query) {
        String rawPage = query == null ? null : query.get("page");
        String rawLimit = query == null ? null : query.get("limit");
        // FAITHFUL(node-quirk): utils/classes/music.js:82-ish calculates skip before parseInt after destructuring defaults.
        int page = jsInt(rawPage, 1);
        int limit = jsInt(rawLimit, 10);
        return new Page((page - 1) * limit, limit);
    }

    private static int jsInt(String value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(value.trim());
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

    private static Map<String, String> form(Map<String, String> body) {
        return body == null ? Map.of() : body;
    }

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private record Page(int skip, int limit) {}
}
