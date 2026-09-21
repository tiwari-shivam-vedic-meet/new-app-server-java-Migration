package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.BsonObjectId;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminSystemService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");
    private static final ObjectId AVAILABILITY_CONSULTANT_ID = new ObjectId("67d72b41f5719fb28799c01e");

    private final MongoTemplate mongo;
    @SuppressWarnings("unused")
    private final AdminMongoSupport support;
    private final PushNotificationService push;

    public AdminSystemService(MongoTemplate mongo, AdminMongoSupport support) {
        this(mongo, support, null);
    }

    @Autowired
    public AdminSystemService(MongoTemplate mongo, AdminMongoSupport support, PushNotificationService push) {
        this.mongo = mongo;
        this.support = support;
        this.push = push;
    }

    public Map<String, Object> createCoupon(Map<String, Object> body) {
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:13-25 ignores req.body and creates a hard-coded coupon_master document.
        Document coupon = new Document("_id", new ObjectId())
                .append("code", "FREE5MINUTES")
                .append("type", "first_purchase")
                .append("valueType", "time")
                .append("value", 300)
                .append("usageLimit", "unlimited")
                .append("isActive", true)
                .append("limitNumber", "")
                .append("hasDateRange", false)
                .append("startDate", "")
                .append("endDate", "")
                .append("description", "Free 5 minutes for first time!");
        mongo.getCollection(Collections.COUPON_MASTERS).insertOne(coupon);
        return coupon;
    }

    public Map<String, Object> couponList(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        String rawPage = q.getOrDefault("page", "0");
        String rawPageSize = q.getOrDefault("pageSize", "1000");
        int page = jsInt(rawPage, 0);
        int pageSize = jsInt(rawPageSize, 1000);
        int skip = jsInt(String.valueOf(page * pageSize), 0);

        Document filterQuery = couponFilter(q);
        Document sortQuery = couponSort(q);

        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:70-74 queries models.coupon (model 'coupon_master') and applies even an empty sort object.
        FindIterable<Document> iterable = mongo.getCollection(Collections.COUPON_MASTERS)
                .find(filterQuery).sort(sortQuery).skip(skip).limit(pageSize);
        List<Document> rows = iterable.into(new ArrayList<>());
        long totalCount = mongo.getCollection(Collections.COUPON_MASTERS).countDocuments(filterQuery);

        Map<String, Object> pagination = new LinkedHashMap<>();
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:77 computes Math.ceil(totalCount / pageSize) with pageSize from the query.
        pagination.put("pageCount", (int) Math.ceil(totalCount / (double) pageSize));
        pagination.put("pageNumber", page);
        pagination.put("totalDocuments", totalCount);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("pagination", pagination);
        return data;
    }

    public Map<String, Object> notification(Map<String, String> query) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "incoming_call");
        data.put("callId", "1234567890");
        data.put("callerName", "John Doe");
        data.put("callerImage", "https://images.unsplash.com/photo-1633332755192-727a05c4013d?w=600&auto=format&fit=crop&q=60&ixlib=rb-4.0.3&ixid=M3wxMjA3fDB8MHxzZWFyY2h8Mnx8dXNlcnN8ZW58MHx8MHx8fDA%3D");
        data.put("sessionId", "session_1234567890");

        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:102-115 ignores req.query and sends this hard-coded test push payload.
        boolean sent = push != null && push.send("user",
                "eH278FeQRbqNWk5CbIudJF:APA91bGNkoX5gcUCAtvmfkjT90wP5py7kV56XerFZUySdUiHPjIdRwHzdL_zC_kar0rtZMw7B0l6stlRjpm-ym1MpFa1NTcWRik6yi2srf2AZCfbzuu65fo",
                "Incoming Call", "Test message", data, null, true, Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        if (sent) {
            result.put("success", true);
            result.put("messageId", true);
        } else {
            result.put("success", false);
            result.put("error", "FCM transport unavailable");
        }
        return result;
    }

    public Map<String, Object> availability(Map<String, String> query) {
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:127 ignores req.query and looks up one hard-coded consultant ObjectId.
        Document result = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", AVAILABILITY_CONSULTANT_ID)).first();
        if (result == null) {
            throw new IllegalStateException("Cannot read properties of null (reading 'availability')");
        }
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:128 reads result.availability but does not use it in the response.
        result.get("availability");
        return result;
    }

    public Map<String, Object> masterGet(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:142-143 queries seed_master by the dynamic 'for' value from req.query.document.
        return mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", q.get("document"))).first();
    }

    public Map<String, Object> masterUpdate(Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        Object updatedData = input.get("updatedData");
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:160 uses typeof(updatedData) !== 'object'; null and arrays are valid, missing/primitive values are invalid.
        if (!input.containsKey("updatedData") || !(updatedData == null || updatedData instanceof Map<?, ?> || updatedData instanceof List<?>)) {
            throw new IllegalArgumentException("Data is not valid!");
        }

        UpdateResult update = null;
        Object document = input.get("document");
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:168-185 only these three document names trigger an upsert; any other document is a successful no-op.
        if ("firstFreeConsultantForAndroid".equals(document)
                || "firstFreeConsultantForIos".equals(document)
                || "consultantMasterSettings".equals(document)) {
            update = mongo.getCollection(Collections.SEED_MASTERS).updateOne(
                    new Document("for", document),
                    new Document("$set", new Document("data", updatedData)),
                    new UpdateOptions().upsert(true));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", update != null && update.getUpsertedId() != null
                ? "Document created successfully" : "Document updated successfully");
        // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:193 includes data: undefined for unknown documents, which JSON omits.
        if (update != null) result.put("data", updateResult(update));
        return result;
    }

    private Document couponFilter(Map<String, String> q) {
        Document filter = new Document();
        String field = q.get("filterField");
        String operator = q.get("filterOperator");
        String value = q.get("filterValue");
        if (truthy(field) && truthy(operator) && truthy(value)) {
            switch (operator) {
                case "equals" -> filter.put(field, value);
                // FAITHFUL(node-quirk): rest-apis/modules/admin/system.js:51-58 uses raw, unescaped regex input.
                case "contains" -> filter.put(field, new Document("$regex", value).append("$options", "i"));
                case "startsWith" -> filter.put(field, new Document("$regex", "^" + value).append("$options", "i"));
                case "endsWith" -> filter.put(field, new Document("$regex", value + "$").append("$options", "i"));
                default -> { }
            }
        }
        return filter;
    }

    private Document couponSort(Map<String, String> q) {
        Document sort = new Document();
        String field = q.get("sortField");
        String direction = q.get("sortDirection");
        if (truthy(field) && truthy(direction) && !"null".equals(field) && !"null".equals(direction)) {
            sort.put(field, "asc".equalsIgnoreCase(direction) ? 1 : -1);
        }
        return sort;
    }

    private Document updateResult(UpdateResult update) {
        Document out = new Document("acknowledged", update.wasAcknowledged())
                .append("matchedCount", update.getMatchedCount())
                .append("modifiedCount", update.getModifiedCount())
                .append("upsertedCount", update.getUpsertedId() == null ? 0 : 1);
        BsonValue upsertedId = update.getUpsertedId();
        if (upsertedId != null) out.append("upsertedId", upsertedId instanceof BsonObjectId bsonId ? bsonId.getValue() : upsertedId);
        return out;
    }

    private static int jsInt(String value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(value.trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static boolean truthy(String value) {
        return value != null && !value.isEmpty();
    }
}
