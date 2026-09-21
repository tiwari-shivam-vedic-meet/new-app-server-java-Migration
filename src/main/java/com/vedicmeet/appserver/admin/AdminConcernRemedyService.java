package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
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
public class AdminConcernRemedyService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminConcernRemedyService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> listAreaOfConcern(Map<String, String> query) {
        Page p = page(query);
        Document params = new Document();
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (search != null && !search.isEmpty()) {
            // FAITHFUL(node-quirk): concern-remedy-service.js listAreaOfConcern builds ".*"+search+".*" unescaped.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }
        List<Document> list = mongo.getCollection(Collections.AREA_OF_CONCERNS).find(params)
                .sort(new Document("createdAt", -1)).skip(p.skip).limit(p.limit).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.AREA_OF_CONCERNS).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", withIdList(list));
        result.put("total", total);
        return result;
    }

    public Map<String, Object> addAreaOfConcern(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document titleExist = mongo.getCollection(Collections.AREA_OF_CONCERNS)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalStateException("TITLE_EXIST");

        // FAITHFUL: AreaOfConcernModel.create(input) keeps only schema paths [title,status] + defaults (area-of-concern-model.js).
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId())
                .append("title", input.getOrDefault("title", ""))
                .append("status", input.containsKey("status") ? toBool(input.get("status")) : true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.AREA_OF_CONCERNS).insertOne(doc);
        return withId(doc);
    }

    public Map<String, Object> updateAreaOfConcern(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document exist = mongo.getCollection(Collections.AREA_OF_CONCERNS)
                .find(new Document("_id", support.id(input.get("areaOfConcernId")))).first();
        if (exist == null) throw new IllegalStateException("AREA_OF_CONCERN_NOT_EXIST");

        Document titleExist = mongo.getCollection(Collections.AREA_OF_CONCERNS).find(
                new Document("_id", new Document("$nin", List.of(support.id(input.get("areaOfConcernId")))))
                        .append("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalStateException("TITLE_EXIST");

        // FAITHFUL: $set:input is strict-cast to [title,status]; areaOfConcernId is stripped; timestamps set updatedAt.
        Document set = new Document();
        if (input.containsKey("title")) set.append("title", input.get("title"));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        set.append("updatedAt", new Date());
        Document updated = mongo.getCollection(Collections.AREA_OF_CONCERNS).findOneAndUpdate(
                new Document("_id", support.id(input.get("areaOfConcernId"))), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withId(updated);
    }

    public Map<String, Object> blockUnblockAreaOfConcern(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document exist = mongo.getCollection(Collections.AREA_OF_CONCERNS)
                .find(new Document("_id", support.id(input.get("areaOfConcernId")))).first();
        if (exist == null) throw new IllegalStateException("AREA_OF_CONCERN_NOT_EXIST");

        Document set = new Document("status", toBool(input.get("status"))).append("updatedAt", new Date());
        Document updated = mongo.getCollection(Collections.AREA_OF_CONCERNS).findOneAndUpdate(
                new Document("_id", support.id(input.get("areaOfConcernId"))), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withId(updated);
    }

    public Map<String, Object> addAreaOfConcernRemedyMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document areaExist = mongo.getCollection(Collections.AREA_OF_CONCERNS)
                .find(new Document("_id", support.id(input.get("areaOfConcernId")))).first();
        if (areaExist == null) throw new IllegalStateException("AREA_OF_CONCERN_NOT_EXIST");

        Document titleExist = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalStateException("TITLE_EXIST");

        // FAITHFUL: AreaOfConcernRemedyModel.create(input) keeps [areaOfConcernId,title,description,status]+defaults.
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId());
        if (input.containsKey("areaOfConcernId")) doc.append("areaOfConcernId", support.id(input.get("areaOfConcernId")));
        doc.append("title", input.getOrDefault("title", ""))
                .append("description", input.getOrDefault("description", ""))
                .append("status", input.containsKey("status") ? toBool(input.get("status")) : true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES).insertOne(doc);
        return withId(doc);
    }

    public Map<String, Object> editAreaOfConcernRemedyMedia(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document remedyExist = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES)
                .find(new Document("_id", support.id(input.get("areaOfConcernRemedyId")))).first();
        // FAITHFUL(node-quirk): concern-remedy-service.js throws AREA_OF_CONCERN_REMEDY_EXIST when the remedy is NOT found.
        if (remedyExist == null) throw new IllegalStateException("AREA_OF_CONCERN_REMEDY_EXIST");

        Document titleExist = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES).find(
                new Document("_id", new Document("$nin", List.of(support.id(input.get("areaOfConcernRemedyId")))))
                        .append("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalStateException("TITLE_EXIST");

        // FAITHFUL: $set:input strict-cast to [areaOfConcernId,title,description,status]; areaOfConcernRemedyId stripped.
        Document set = new Document();
        if (input.containsKey("areaOfConcernId")) set.append("areaOfConcernId", support.id(input.get("areaOfConcernId")));
        if (input.containsKey("title")) set.append("title", input.get("title"));
        if (input.containsKey("description")) set.append("description", input.get("description"));
        if (input.containsKey("status")) set.append("status", toBool(input.get("status")));
        set.append("updatedAt", new Date());
        Document updated = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES).findOneAndUpdate(
                new Document("_id", support.id(input.get("areaOfConcernRemedyId"))), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withId(updated);
    }

    public Map<String, Object> blockUnblockAreaOfConcernRemedyMedia(Map<String, Object> body) {
        // FAITHFUL(node-bug): concern-remedy.js GET /remedy/block_unblock calls
        // ConcernRemedyService.blockUnblockAreaOfConcernRemedyMedia, which is never defined
        // (the class only has blockAreaOfConcernRemedyMedia) -> TypeError -> 500.
        throw new IllegalStateException("ConcernRemedyService.blockUnblockAreaOfConcernRemedyMedia is not a function");
    }

    public Map<String, Object> listAreaOfConcernRemedyMedia(Map<String, String> query) {
        Page p = page(query);
        // FAITHFUL: params seeded with areaOfConcernId (cast to ObjectId by the schema path).
        Document params = new Document("areaOfConcernId",
                support.id(query == null ? null : query.get("areaOfConcernId")));
        String search = query == null ? "" : query.getOrDefault("search", "");
        if (search != null && !search.isEmpty()) {
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }
        List<Document> list = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES).find(params)
                .sort(new Document("createdAt", -1)).skip(p.skip).limit(p.limit).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", withIdList(list));
        result.put("total", total);
        return result;
    }

    // ---- helpers ----

    private List<Document> withIdList(List<Document> docs) {
        List<Document> out = new ArrayList<>(docs.size());
        for (Document d : docs) out.add(withId(d));
        return out;
    }

    // toJSON:{virtuals:true} exposes Mongoose's default `id` virtual (_id hex) on every returned doc.
    private Document withId(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Page page(Map<String, String> query) {
        int page = jsInt(query == null ? null : query.get("page"), 1);
        int limit = jsInt(query == null ? null : query.get("limit"), 10);
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

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private record Page(int skip, int limit) {}
}
