package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mobile port of Node {@code utils/classes/kundali.js}. */
@Service
public class KundaliService {
    private final MongoTemplate mongo;
    private final CleverTapClient cleverTap;
    private final YogaService yoga;

    public KundaliService(MongoTemplate mongo, CleverTapClient cleverTap, YogaService yoga) {
        this.mongo = mongo;
        this.cleverTap = cleverTap;
        this.yoga = yoga;
    }

    public Document add(Map<String, Object> input, Document actor) {
        Document data = normalized(input);
        validateCreate(data);
        data.put("userId", actor.get("_id"));
        data.putIfAbsent("status", true);
        Date now = new Date();
        data.put("createdAt", now);
        data.put("updatedAt", now);
        mongo.getCollection(Collections.USER_KUNDALIS).insertOne(data);
        return data;
    }

    public Document update(Map<String, Object> input) {
        Document data = normalized(input);
        ObjectId id = id(data.remove("kundaliId"), "kundaliId");
        data.put("updatedAt", new Date());
        Document updated = mongo.getCollection(Collections.USER_KUNDALIS).findOneAndUpdate(
                new Document("_id", id), new Document("$set", data),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalArgumentException("KUNDLI_NOT_EXIST");
        return updated;
    }

    public Map<String, Object> list(Document actor, String requestId, Integer pageParam,
                                    Integer limitParam, String search) {
        Page page = page(pageParam, limitParam, 100);
        Document filter;
        if (requestId != null && !requestId.isBlank()) {
            filter = new Document("consultantFormRequestId", maybeId(requestId)).append("status", true);
        } else filter = new Document("userId", actor.get("_id")).append("status", true);
        if (search != null && !search.isBlank()) filter.put("$or", List.of(
                new Document("name", new Document("$regex", ".*" + search.trim() + ".*")
                        .append("$options", "i"))));
        List<Document> list = mongo.getCollection(Collections.USER_KUNDALIS).find(filter)
                .projection(new Document("consultantFormRequestId", 0).append("userId", 0)
                        .append("__v", 0).append("updatedAt", 0))
                .sort(new Document("createdAt", -1)).skip(page.skip()).limit(page.limit)
                .into(new ArrayList<>());
        return result(list, mongo.getCollection(Collections.USER_KUNDALIS).countDocuments(filter));
    }

    public Document details(String kundaliId) {
        Document found = mongo.getCollection(Collections.USER_KUNDALIS)
                .find(new Document("_id", id(kundaliId, "kundaliId"))).first();
        if (found == null) throw new IllegalArgumentException("KUNDLI_NOT_EXIST");
        return found;
    }

    public Document addMatch(Map<String, Object> input, Document actor) {
        Document data = input == null ? new Document() : new Document(input);
        data.put("userId", actor.get("_id"));
        data.putIfAbsent("status", true);
        Date now = new Date();
        data.put("createdAt", now);
        data.put("updatedAt", now);
        mongo.getCollection(Collections.MATCH_MAKINGS).insertOne(data);
        uploadMatchEvent(actor);
        return data;
    }

    public Map<String, Object> matches(Document actor, Integer pageParam, Integer limitParam) {
        Page page = page(pageParam, limitParam, 10);
        Document filter = new Document("userId", actor.get("_id"));
        List<Document> list = mongo.getCollection(Collections.MATCH_MAKINGS).find(filter)
                .sort(new Document("createdAt", -1)).skip(page.skip()).limit(page.limit)
                .into(new ArrayList<>());
        return result(list, mongo.getCollection(Collections.MATCH_MAKINGS).countDocuments(filter));
    }

    public List<Document> yogas(List<Map<String, Object>> planets) { return yoga.applied(planets); }

    private Document normalized(Map<String, Object> input) {
        Document data = input == null ? new Document() : new Document(input);
        if (data.get("dateOfBirth") != null) data.put("dateOfBirth", normalizeDate(data.get("dateOfBirth")));
        if (data.get("consultantFormRequestId") != null)
            data.put("consultantFormRequestId", maybeId(String.valueOf(data.get("consultantFormRequestId"))));
        return data;
    }

    private Date normalizeDate(Object raw) {
        if (raw instanceof Date d) return d;
        String value = String.valueOf(raw);
        try { return Date.from(Instant.parse(value)); }
        catch (Exception ignored) { }
        try { return Date.from(OffsetDateTime.parse(value).toInstant()); }
        catch (Exception ignored) { }
        try { return Date.from(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)); }
        catch (Exception e) { throw new IllegalArgumentException("dateOfBirth must be a valid date"); }
    }

    private void validateCreate(Document data) {
        requireText(data, "name");
        if (data.getString("name").length() > 100) throw new IllegalArgumentException("name is too long");
        String gender = requireText(data, "gender");
        if (!List.of("male", "female", "non-binary").contains(gender))
            throw new IllegalArgumentException("gender is invalid");
        if (data.get("dateOfBirth") == null) throw new IllegalArgumentException("dateOfBirth is required");
        requireText(data, "timeOfBirth");
        requireText(data, "placeOfBirth");
    }

    private String requireText(Document data, String key) {
        String value = data.get(key) == null ? "" : String.valueOf(data.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private void uploadMatchEvent(Document actor) {
        Object details = actor.get("details");
        String phone = details instanceof Document d ? d.getString("phone") : null;
        if (phone != null && cleverTap.isReady()) cleverTap.uploadEvent(phone, "MATCH_MAKING",
                Map.of("action", "match_making_accessed", "timestamp", Instant.now().toString(),
                        "source", "server"));
    }

    private Object maybeId(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }

    private ObjectId id(Object value, String field) {
        String text = value == null ? "" : String.valueOf(value);
        if (!ObjectId.isValid(text)) throw new IllegalArgumentException(field + " is required");
        return new ObjectId(text);
    }

    private Page page(Integer p, Integer l, int defaultLimit) {
        int page = p == null ? 1 : p, limit = l == null ? defaultLimit : l;
        if (page < 1 || limit < 1) throw new IllegalArgumentException("Invalid pagination");
        return new Page(page, limit);
    }

    private Map<String, Object> result(List<Document> list, long total) {
        Map<String, Object> r = new LinkedHashMap<>(); r.put("list", list); r.put("total", total); return r;
    }

    private record Page(int page, int limit) { int skip() { return (page - 1) * limit; } }
}
