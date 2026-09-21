package com.vedicmeet.appserver.admin;

import com.mongodb.client.MongoCollection;
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
public class AdminNotificationService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");
    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminNotificationService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> list(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Page page = page(input);
        Document params = new Document("notificationType", "instant");
        String search = str(input.getOrDefault("search", ""));
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/notification.js:187-190 builds an unescaped contains regex.
            params.put("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = scheduledCollection().find(params)
                .sort(new Document("createdAt", -1)).skip(page.skip).limit(page.limit)
                .into(new ArrayList<>()).stream().map(this::withId).toList();
        long total = scheduledCollection().countDocuments(params);
        return listResult(list, total);
    }

    public Map<String, Object> send(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        // FAITHFUL(node-quirk): rest-apis/modules/admin/notification.js:27-63 registers POST /send twice; Express uses the first handler.
        // FAITHFUL(node-quirk): utils/classes/notification.js:584 sets activeUserType='all' and notificationType='instant' before create().
        Document payload = new Document();
        putIfNotNull(payload, "title", trimOrNull(input.get("title")));
        putIfNotNull(payload, "message", trimOrNull(input.get("message")));
        putIfNotNull(payload, "userType", input.get("userType"));
        payload.put("activeUserType", input.getOrDefault("activeUserType", "all"));
        payload.put("notificationType", input.getOrDefault("notificationType", "instant"));

        Document saved = scheduledNotificationCreate(payload);
        scheduledCollection().insertOne(saved);

        // FAITHFUL(node-quirk): utils/classes/notification.js:597-602 posts to MICRO_SERVICE_URL /notification/instant-send.
        // Transport is intentionally a no-op in this port; the database create above is the local side effect.
        return transportNoop("instant-send", saved.get("_id"), payload);
    }

    public Map<String, Object> delete(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("notificationId"));
        Document existing = notificationCollection().find(new Document("_id", id)).first();
        if (existing == null) throw new IllegalStateException("NOTIFICATION_NOT_EXIST");

        // FAITHFUL(node-quirk): utils/classes/notification.js:223-225 deletes then returns undefined.
        notificationCollection().findOneAndDelete(new Document("_id", id));
        return new LinkedHashMap<>();
    }

    public Map<String, Object> adminNotification(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Page page = page(input);
        List<Document> rows = notificationCollection().aggregate(adminNotificationPipeline(page.skip, page.limit))
                .into(new ArrayList<>());

        List<?> list = List.of();
        long total = 0;
        if (!rows.isEmpty()) {
            Document facet = rows.get(0);
            list = documents(facet.get("list")).stream().map(this::withId).toList();
            List<Document> count = documents(facet.get("count"));
            if (!count.isEmpty()) total = longOf(count.get(0).get("total"));
        }
        return listResult(list, total);
    }

    public Map<String, Object> readNotification(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object id = support.id(input.get("notificationId"));
        Document existing = notificationCollection().find(new Document("_id", id)).first();
        if (existing != null) {
            // FAITHFUL(node-bug): rest-apis/modules/admin/notification.js:108 calls markRead(req.body) without the required user argument; utils/classes/notification.js:502 then reads user._id.
            throw new IllegalStateException("Cannot read properties of undefined (reading '_id')");
        }
        return null;
    }

    public Map<String, Object> count(Map<String, Object> body) {
        // FAITHFUL(node-bug): rest-apis/modules/admin/notification.js:128 calls NotificationService.notificationCount, but utils/classes/notification.js defines count/countUser, not notificationCount.
        throw new IllegalStateException("NotificationService.notificationCount is not a function");
    }

    public Map<String, Object> scheduled(Map<String, String> query) {
        Map<String, Object> input = queryBody(query);
        Page page = page(input);
        Document params = new Document("notificationType", "scheduled");
        String search = str(input.getOrDefault("search", ""));
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/notification.js:523-526 builds an unescaped contains regex.
            params.put("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = scheduledCollection().aggregate(scheduledPipeline(params, page.skip, page.limit))
                .into(new ArrayList<>()).stream().map(this::withId).toList();
        long total = scheduledCollection().countDocuments(params);
        return listResult(list, total);
    }

    public Map<String, Object> schedule(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document payload = new Document();
        putIfNotNull(payload, "title", trimOrNull(input.get("title")));
        putIfNotNull(payload, "message", trimOrNull(input.get("message")));
        putIfNotNull(payload, "scheduledAt", input.get("scheduledAt"));
        putIfNotNull(payload, "activeUserType", input.get("activeUserType"));
        putIfNotNull(payload, "userType", input.get("userType"));

        // FAITHFUL(node-quirk): utils/classes/notification.js:550-558 delegates all persistence to MICRO_SERVICE_URL /notification/schedule.
        // Transport is intentionally a no-op in this port, so no local Mongo write is made here.
        return transportNoop("schedule", null, payload);
    }

    public Map<String, Object> cancelScheduled(Map<String, String> query) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (query != null && query.containsKey("notificationId")) payload.put("notificationId", query.get("notificationId"));
        // FAITHFUL(node-quirk): utils/classes/notification.js:572-575 delegates cancellation to MICRO_SERVICE_URL /notification/scheduled/cancel.
        // Transport is intentionally a no-op in this port, so no local Mongo write is made here.
        return transportNoop("scheduled-cancel", query == null ? null : query.get("notificationId"), payload);
    }

    List<Document> adminNotificationPipeline(int skipIndex, int limit) {
        return List.of(
                new Document("$match", new Document("$or", List.of(new Document("userType", "admin"), new Document("senderType", "system")))),
                new Document("$project", new Document("title", 1).append("message", 1)
                        .append("createdAt", 1).append("readByAdmin", 1)),
                new Document("$facet", new Document("list", List.of(
                        new Document("$sort", new Document("createdAt", -1)),
                        new Document("$skip", skipIndex),
                        new Document("$limit", limit)))
                        .append("count", List.of(new Document("$count", "total")))));
    }

    List<Document> scheduledPipeline(Document params, int skipIndex, int limit) {
        return List.of(
                new Document("$match", params),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit));
    }

    private MongoCollection<Document> scheduledCollection() {
        return mongo.getCollection(Collections.SCHEDULED_NOTIFICATIONS);
    }

    private MongoCollection<Document> notificationCollection() {
        return mongo.getCollection(Collections.NOTIFICATIONS);
    }

    private Document scheduledNotificationCreate(Document input) {
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId());
        putIfNotNull(doc, "title", trimOrNull(input.get("title")));
        putIfNotNull(doc, "message", trimOrNull(input.get("message")));
        putIfNotNull(doc, "scheduledAt", dateOrOriginal(input.get("scheduledAt")));
        putIfNotNull(doc, "notificationType", input.getOrDefault("notificationType", "scheduled"));
        putIfNotNull(doc, "activeUserType", input.getOrDefault("activeUserType", "all"));
        putIfNotNull(doc, "userType", input.get("userType"));
        doc.put("status", input.getOrDefault("status", "scheduled"));
        putIfNotNull(doc, "createdBy", idOrOriginal(input.get("createdBy")));
        doc.put("createdAt", now);
        doc.put("updatedAt", now);
        return doc;
    }

    private Map<String, Object> transportNoop(String operation, Object notificationId, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        if (notificationId != null) result.put("notificationId", str(notificationId));
        result.put("operation", operation);
        result.put("transport", "noop");
        result.put("payload", new Document(payload));
        return result;
    }

    private Map<String, Object> listResult(List<?> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private Document withId(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Document> documents(Object value) {
        if (value instanceof List<?> list) return (List<Document>) list;
        return List.of();
    }

    private Page page(Map<String, Object> input) {
        Object rawPage = input.get("page");
        Object rawLimit = input.get("limit");
        // FAITHFUL(node-quirk): utils/classes/notification.js:180/236/516 computes skipIndex from defaulted values before parseInt.
        int page = jsInt(rawPage, 1);
        int limit = jsInt(rawLimit, 10);
        return new Page((page - 1) * limit, limit);
    }

    private int jsInt(Object value, int def) {
        if (value == null) return def;
        if (value instanceof Number number) return number.intValue();
        Matcher m = LEADING_INT.matcher(String.valueOf(value).trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static Object dateOrOriginal(Object value) {
        if (value == null || value instanceof Date) return value;
        if (value instanceof Number number) return new Date(number.longValue());
        String text = String.valueOf(value);
        try { return Date.from(Instant.parse(text)); }
        catch (Exception ignored) { return value; }
    }

    private Object idOrOriginal(Object value) {
        return value == null ? null : support.id(value);
    }

    private static void putIfNotNull(Document doc, String key, Object value) {
        if (value != null) doc.put(key, value);
    }

    private static String trimOrNull(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private static Map<String, Object> queryBody(Map<String, String> query) {
        if (query == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(query);
        return result;
    }

    private record Page(int skip, int limit) {}
}