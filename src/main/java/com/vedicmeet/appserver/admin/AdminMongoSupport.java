package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Small, bounded Mongo helper used by native admin modules. */
@Component
public class AdminMongoSupport {

    private final MongoTemplate mongo;

    public AdminMongoSupport(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Document findById(String collection, Object rawId) {
        if (rawId == null || String.valueOf(rawId).isBlank()) return null;
        Object identifier = id(rawId);
        Document found = mongo.getCollection(collection).find(new Document("_id", identifier)).first();
        if (found == null && identifier instanceof ObjectId) {
            found = mongo.getCollection(collection).find(new Document("_id", String.valueOf(rawId))).first();
        }
        return found;
    }

    public Document requireById(String collection, Object rawId, String error) {
        Document found = findById(collection, rawId);
        if (found == null) throw new IllegalStateException(error);
        return found;
    }

    public Document updateById(String collection, Object rawId, Document changes, String notFoundError) {
        Document set = new Document(changes).append("updatedAt", new Date());
        Document updated = mongo.getCollection(collection).findOneAndUpdate(
                new Document("_id", id(rawId)), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException(notFoundError);
        return updated;
    }

    public Map<String, Object> page(String collection, Document filter, Document sort,
                                    int page, int limit) {
        int bounded = safeLimit(limit);
        List<Document> list = mongo.getCollection(collection).find(filter == null ? new Document() : filter)
                .sort(sort == null || sort.isEmpty() ? new Document("createdAt", -1) : sort)
                .skip((Math.max(1, page) - 1) * bounded).limit(bounded).into(new ArrayList<>());
        return page(list, mongo.getCollection(collection)
                .countDocuments(filter == null ? new Document() : filter));
    }

    public Map<String, Object> page(List<Document> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public void addSearch(Document filter, String rawSearch, List<String> fields) {
        if (rawSearch == null || rawSearch.isBlank() || fields == null || fields.isEmpty()) return;
        String escaped = Pattern.quote(rawSearch.trim());
        List<Document> terms = fields.stream().map(field -> new Document(field,
                new Document("$regex", escaped).append("$options", "i"))).toList();
        if (terms.size() == 1) filter.putAll(terms.get(0));
        else filter.append("$or", terms);
    }

    public Document dynamicFilter(String field, String operator, String value) {
        Document filter = new Document();
        if (field == null || field.isBlank() || value == null || value.isBlank()) return filter;
        if (!field.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("INVALID_FILTER_FIELD");
        String escaped = Pattern.quote(value);
        switch (operator == null ? "equals" : operator) {
            case "contains" -> filter.append(field, new Document("$regex", escaped).append("$options", "i"));
            case "startsWith" -> filter.append(field, new Document("$regex", "^" + escaped).append("$options", "i"));
            case "endsWith" -> filter.append(field, new Document("$regex", escaped + "$").append("$options", "i"));
            default -> filter.append(field, value);
        }
        return filter;
    }

    public Document dynamicSort(String field, String direction) {
        if (field == null || field.isBlank() || "null".equals(field)) return new Document("createdAt", -1);
        if (!field.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("INVALID_SORT_FIELD");
        return new Document(field, "asc".equalsIgnoreCase(direction) ? 1 : -1);
    }

    public Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String text = raw == null ? "" : String.valueOf(raw);
        return ObjectId.isValid(text) ? new ObjectId(text) : raw;
    }

    public int safeLimit(int limit) { return Math.max(1, Math.min(limit <= 0 ? 10 : limit, 1000)); }

    public double number(Object value, String error) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { throw new IllegalArgumentException(error); }
    }
}
