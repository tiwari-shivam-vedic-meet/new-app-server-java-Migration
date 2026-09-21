package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminNotificationMessageService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");
    private static final Pattern TIMING = Pattern.compile("^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$");
    private static final Set<String> ROUTE_CATEGORIES = Set.of("morning", "afternoon", "evening", "night",
            "breakfast", "lunch", "dinner", "sunrise", "sunset", "weekly_monday", "weekly_weekend",
            "monthly_first", "monthly_mid", "monthly_last", "astrological_weekly", "astrological_monthly",
            "custom");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminNotificationMessageService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> list(Map<String, String> query) {
        Page page = page(query);
        Document filter = listFilter(query);
        String sortBy = query == null ? "createdAt" : query.getOrDefault("sortBy", "createdAt");
        String sortOrder = query == null ? "desc" : query.getOrDefault("sortOrder", "desc");
        Document sort = new Document(sortBy, "desc".equals(sortOrder) ? -1 : 1);

        // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:47-55 uses find().sort().skip().limit().lean() plus a separate count.
        List<Document> messages = collection().find(filter).sort(sort).skip(page.skip).limit(page.limit)
                .into(new ArrayList<>()).stream().map(this::withId).toList();
        long total = collection().countDocuments(filter);
        long totalPages = (long) Math.ceil((double) total / page.rawLimit);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("currentPage", page.rawPage);
        pagination.put("totalPages", totalPages);
        pagination.put("totalItems", total);
        pagination.put("itemsPerPage", page.limit);
        pagination.put("hasNextPage", page.rawPage < totalPages);
        pagination.put("hasPrevPage", page.rawPage > 1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        result.put("pagination", pagination);
        return result;
    }

    public Map<String, Object> stats() {
        // FAITHFUL(node-quirk): utils/models/notification-message-model.js:219-239 getMessageStats() is an aggregate grouped by category.
        List<Document> detailedStats = collection().aggregate(messageStatsPipeline()).into(new ArrayList<>());
        long totalMessages = collection().countDocuments();
        long activeMessages = collection().countDocuments(new Document("isActive", true));

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalMessages", totalMessages);
        overview.put("activeMessages", activeMessages);
        overview.put("inactiveMessages", totalMessages - activeMessages);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", overview);
        result.put("categoryStats", collection().aggregate(categoryStatsPipeline()).into(new ArrayList<>()));
        result.put("languageStats", collection().aggregate(simpleCountPipeline("language")).into(new ArrayList<>()));
        result.put("timingStats", collection().aggregate(simpleCountPipeline("timing")).into(new ArrayList<>()));
        result.put("detailedStats", detailedStats);
        return result;
    }

    public Map<String, Object> categories() {
        // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:88 is registered before this
        // one-segment GET, so Express would route GET /categories through /:id and return "Invalid message ID".
        // The existing Spring controller exposes the exact route, so this method preserves the handler body.
        // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:489-500 route list omits schema categories such as inactive_user.
        return data(List.of("morning", "afternoon", "evening", "night", "breakfast", "lunch", "dinner",
                "sunrise", "sunset", "weekly_monday", "weekly_weekend", "monthly_first", "monthly_mid",
                "monthly_last", "astrological_weekly", "astrological_monthly", "custom"));
    }

    public Map<String, Object> languages() {
        // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:88 shadows this one-segment GET in Express;
        // Spring's exact mapping reaches this method, so the route handler's body is ported here.
        return data(List.of(
                new Document("code", "hi").append("name", "Hindi"),
                new Document("code", "en").append("name", "English"),
                new Document("code", "gu").append("name", "Gujarati"),
                new Document("code", "bn").append("name", "Bengali"),
                new Document("code", "ta").append("name", "Tamil"),
                new Document("code", "te").append("name", "Telugu"),
                new Document("code", "ml").append("name", "Malayalam"),
                new Document("code", "kn").append("name", "Kannada"),
                new Document("code", "mr").append("name", "Marathi"),
                new Document("code", "pa").append("name", "Punjabi")));
    }

    public Map<String, Object> targetAudiences() {
        // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:88 shadows this one-segment GET in Express;
        // Spring's exact mapping reaches this method, so the route handler's body is ported here.
        return data(List.of("all", "new_users", "active_users", "premium_users", "consultants"));
    }

    public Map<String, Object> get(String id) {
        requireValidId(id);
        Document message = collection().find(new Document("_id", new ObjectId(id))).first();
        if (message == null) throw new IllegalStateException("Message not found");
        return withId(message);
    }

    public Map<String, Object> create(String adminId, Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        if (isBlank(input.get("title")) || isBlank(input.get("body")) || isBlank(input.get("category"))) {
            throw new IllegalArgumentException("Title, body, and category are required");
        }
        if (!ROUTE_CATEGORIES.contains(str(input.get("category")))) throw new IllegalArgumentException("Invalid category");
        if (present(input.get("timing")) && !TIMING.matcher(str(input.get("timing"))).matches()) {
            throw new IllegalArgumentException("Invalid timing format. Use HH:MM format");
        }

        Date now = new Date();
        // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:176-192 constructs a model with route defaults before save().
        // FAITHFUL(node-quirk): utils/models/notification-message-model.js:4-104 save() persists schema paths only and applies defaults/timestamps.
        Document doc = new Document("_id", new ObjectId())
                .append("title", trim(input.get("title")))
                .append("body", trim(input.get("body")))
                .append("category", str(input.get("category")))
                .append("priority", jsInt(input.getOrDefault("priority", 1), 1))
                .append("language", str(input.getOrDefault("language", "hi")))
                .append("targetAudience", str(input.getOrDefault("targetAudience", "all")))
                .append("screen", trim(input.getOrDefault("screen", "Home")))
                .append("data", input.getOrDefault("data", new Document()))
                .append("tags", trimmedList(input.getOrDefault("tags", List.of())))
                .append("isActive", true)
                .append("usageCount", 0)
                .append("lastUsed", null)
                .append("createdBy", adminId == null ? null : support.id(adminId))
                .append("createdAt", now)
                .append("updatedAt", now);
        putIfPresent(doc, "timing", input.get("timing"));
        putParsedIfTruthy(doc, "dayOfMonth", input.get("dayOfMonth"));
        putParsedIfTruthy(doc, "dayOfWeek", input.get("dayOfWeek"));
        putParsedIfTruthy(doc, "month", input.get("month"));

        collection().insertOne(doc);
        return withId(doc);
    }

    public Map<String, Object> update(String id, Map<String, Object> body) {
        requireValidId(id);
        Document existing = collection().find(new Document("_id", new ObjectId(id))).first();
        if (existing == null) throw new IllegalStateException("Message not found");

        Document set = updateSet(objectBody(body));
        Document unset = updateUnset(objectBody(body));
        set.put("updatedAt", new Date());
        Document update = new Document("$set", set);
        if (!unset.isEmpty()) update.put("$unset", unset);
        // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:241-267 does findById(), mutates provided fields, then save() returns the updated doc.
        Document updated = collection().findOneAndUpdate(new Document("_id", new ObjectId(id)), update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withId(updated == null ? existing : updated);
    }

    public Map<String, Object> delete(String id) {
        requireValidId(id);
        Document deleted = collection().findOneAndDelete(new Document("_id", new ObjectId(id)));
        if (deleted == null) throw new IllegalStateException("Message not found");
        // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:306-309 returns no data payload.
        return new LinkedHashMap<>();
    }

    public Map<String, Object> bulk(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object rawIds = input.get("messageIds");
        if (!present(input.get("operation")) || !(rawIds instanceof List<?> ids)) {
            throw new IllegalArgumentException("Operation and message IDs are required");
        }
        Document filter = new Document("_id", new Document("$in", ids.stream().map(support::id).toList()));
        Object operation = input.get("operation");
        long matched = 0;
        long modified = 0;
        Long deleted = null;

        switch (str(operation)) {
            case "activate" -> {
                // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:336-340 only sets isActive true.
                UpdateResult result = collection().updateMany(filter,
                        new Document("$set", new Document("isActive", true).append("updatedAt", new Date())));
                matched = result.getMatchedCount();
                modified = result.getModifiedCount();
            }
            case "deactivate" -> {
                UpdateResult result = collection().updateMany(filter,
                        new Document("$set", new Document("isActive", false).append("updatedAt", new Date())));
                matched = result.getMatchedCount();
                modified = result.getModifiedCount();
            }
            case "delete" -> {
                DeleteResult result = collection().deleteMany(filter);
                deleted = result.getDeletedCount();
            }
            case "update" -> {
                if (!input.containsKey("data") || input.get("data") == null) {
                    throw new IllegalArgumentException("Data is required for update operation");
                }
                // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:363-366 uses $set:data; Mongoose strict-casts schema paths.
                Document set = schemaSet(mapValue(input.get("data")));
                set.append("updatedAt", new Date());
                UpdateResult result = collection().updateMany(filter, new Document("$set", set));
                matched = result.getMatchedCount();
                modified = result.getModifiedCount();
            }
            default -> throw new IllegalArgumentException("Invalid operation");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matchedCount", matched);
        data.put("modifiedCount", modified);
        data.put("deletedCount", deleted);
        return data;
    }

    public Map<String, Object> resetUsage(Map<String, Object> body) {
        Object category = objectBody(body).get("category");
        Document filter = present(category) ? new Document("category", category) : new Document();
        // FAITHFUL(node-quirk): utils/models/notification-message-model.js:242-250 resetUsageCounts(category=null) filters by category only when truthy.
        UpdateResult result = collection().updateMany(filter, new Document("$set",
                new Document("usageCount", 0).append("lastUsed", null).append("updatedAt", new Date())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matchedCount", result.getMatchedCount());
        data.put("modifiedCount", result.getModifiedCount());
        data.put("deletedCount", null);
        return data;
    }

    public Map<String, Object> test(String id) {
        requireValidId(id);
        Document message = collection().find(new Document("_id", new ObjectId(id))).first();
        if (message == null) throw new IllegalStateException("Message not found");

        String targetAudience = str(message.get("targetAudience"));
        Document userFilter;
        String userCollection = Collections.USERS;
        if ("all".equals(targetAudience)) {
            userFilter = new Document();
        } else if ("new_users".equals(targetAudience) || "inactive_users".equals(targetAudience)) {
            // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:605-615 references undeclared twentyFourHoursAgo before User.find().
            throw new IllegalStateException("twentyFourHoursAgo is not defined");
        } else if ("active_users".equals(targetAudience) || "premium_users".equals(targetAudience)) {
            userFilter = new Document("notificationPreferences.dailyNotifications", true);
        } else if ("consultants".equals(targetAudience)) {
            userCollection = Collections.CONSULTANTS;
            userFilter = new Document("notificationPreferences.dailyNotifications", true);
        } else {
            userFilter = null;
        }

        List<Document> users = userFilter == null ? List.of()
                : mongo.getCollection(userCollection).find(userFilter).into(new ArrayList<>());
        if (users.isEmpty()) throw new IllegalStateException("No users found");
        // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:634-637 calls sendPushNotification(users,payload).
        // External FCM transport is intentionally a no-op seam here; all local DB reads above are preserved.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", message.get("title"));
        data.put("body", message.get("body"));
        data.put("category", message.get("category"));
        data.put("timing", message.get("timing"));
        data.put("targetAudience", message.get("targetAudience"));
        return data;
    }

    public Map<String, Object> importMessages(String adminId, Map<String, Object> body) {
        Object raw = objectBody(body).get("messages");
        if (!(raw instanceof List<?> messages) || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages array is required");
        }
        List<String> errors = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = mapValue(messages.get(i));
            if (isBlank(msg.get("title")) || isBlank(msg.get("body")) || isBlank(msg.get("category"))) {
                errors.add("Row " + (i + 1) + ": Missing required fields");
                continue;
            }
            if (!ROUTE_CATEGORIES.contains(str(msg.get("category")))) {
                errors.add("Row " + (i + 1) + ": Invalid category");
                continue;
            }
            Map<String, Object> valid = new LinkedHashMap<>(msg);
            valid.put("createdBy", adminId);
            valid.put("priority", jsIntOr(valid.get("priority"), 1));
            valid.put("language", present(valid.get("language")) ? valid.get("language") : "hi");
            valid.put("targetAudience", present(valid.get("targetAudience")) ? valid.get("targetAudience") : "all");
            valid.put("screen", present(valid.get("screen")) ? valid.get("screen") : "Home");
            // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:702-709 spreads msg before overriding five fields.
            docs.add(insertDoc(valid, adminId));
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException("Validation errors found");

        collection().insertMany(docs);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imported", docs.size());
        data.put("total", messages.size());
        return data;
    }

    public String export(Map<String, String> query) {
        // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:88 shadows GET /export in Express;
        // the existing Spring controller directly exposes CSV export, so the CSV branch body is ported.
        Document filter = new Document();
        if (query != null && present(query.get("category"))) filter.put("category", query.get("category"));
        if (query != null && present(query.get("language"))) filter.put("language", query.get("language"));
        List<Document> messages = collection().find(filter).into(new ArrayList<>());
        if (messages.isEmpty()) {
            // FAITHFUL(node-bug): rest-apis/modules/admin/notification-message.js:776-777 Object.keys(csvData[0]) throws for empty CSV exports.
            throw new IllegalStateException("Cannot convert undefined or null to object");
        }
        String header = "title,body,category,timing,dayOfMonth,dayOfWeek,month,priority,language,targetAudience,screen,tags,isActive";
        List<String> lines = new ArrayList<>();
        lines.add(header);
        for (Document msg : messages) {
            // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:756-779 quotes values without CSV escaping.
            lines.add(List.of(
                    csv(msg.get("title")),
                    csv(msg.get("body")),
                    csv(msg.get("category")),
                    csv(msg.get("timing")),
                    csv(orBlank(msg.get("dayOfMonth"))),
                    csv(orBlank(msg.get("dayOfWeek"))),
                    csv(orBlank(msg.get("month"))),
                    csv(msg.get("priority")),
                    csv(msg.get("language")),
                    csv(msg.get("targetAudience")),
                    csv(msg.get("screen")),
                    csv(String.join(", ", stringList(msg.get("tags")))),
                    csv(msg.get("isActive"))).stream().reduce((a, b) -> a + "," + b).orElse(""));
        }
        return String.join("\n", lines);
    }

    List<Document> messageStatsPipeline() {
        return List.of(
                Document.parse("{ $group: { _id: '$category', count: { $sum: 1 }, activeCount: { $sum: { $cond: ['$isActive', 1, 0] } }, totalUsage: { $sum: '$usageCount' }, avgUsage: { $avg: '$usageCount' } } }"),
                Document.parse("{ $sort: { count: -1 } }"));
    }

    List<Document> categoryStatsPipeline() {
        return List.of(
                Document.parse("{ $group: { _id: '$category', count: { $sum: 1 }, activeCount: { $sum: { $cond: ['$isActive', 1, 0] } }, avgPriority: { $avg: '$priority' } } }"),
                Document.parse("{ $sort: { count: -1 } }"));
    }

    List<Document> simpleCountPipeline(String field) {
        return List.of(
                new Document("$group", new Document("_id", "$" + field).append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)));
    }

    private Document listFilter(Map<String, String> query) {
        Document filter = new Document();
        if (query == null) return filter;
        if (present(query.get("category"))) filter.put("category", query.get("category"));
        if (present(query.get("language"))) filter.put("language", query.get("language"));
        if (query.containsKey("isActive")) {
            // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:30 only the literal string 'true' becomes true.
            filter.put("isActive", "true".equals(query.get("isActive")));
        }
        if (present(query.get("search"))) {
            String search = query.get("search");
            // FAITHFUL(node-quirk): rest-apis/modules/admin/notification-message.js:33-38 builds raw, unescaped case-insensitive regexes.
            filter.put("$or", List.of(
                    new Document("title", new Document("$regex", search).append("$options", "i")),
                    new Document("body", new Document("$regex", search).append("$options", "i")),
                    new Document("tags", new Document("$in", List.of(Pattern.compile(search, Pattern.CASE_INSENSITIVE))))));
        }
        return filter;
    }

    private Document insertDoc(Map<String, Object> input, String adminId) {
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId())
                .append("isActive", input.containsKey("isActive") ? bool(input.get("isActive")) : true)
                .append("priority", jsIntOr(input.get("priority"), 1))
                .append("language", str(input.getOrDefault("language", "en")))
                .append("targetAudience", str(input.getOrDefault("targetAudience", "all")))
                .append("screen", trim(input.getOrDefault("screen", "Home")))
                .append("data", input.getOrDefault("data", new Document()))
                .append("tags", trimmedList(input.getOrDefault("tags", List.of())))
                .append("usageCount", input.containsKey("usageCount") ? jsIntOr(input.get("usageCount"), 0) : 0)
                .append("lastUsed", input.getOrDefault("lastUsed", null))
                .append("createdBy", adminId == null ? null : support.id(adminId))
                .append("createdAt", now)
                .append("updatedAt", now);
        putIfPresent(doc, "title", trim(input.get("title")));
        putIfPresent(doc, "body", trim(input.get("body")));
        putIfPresent(doc, "category", input.get("category"));
        putIfPresent(doc, "timing", input.get("timing"));
        putParsedIfTruthy(doc, "dayOfMonth", input.get("dayOfMonth"));
        putParsedIfTruthy(doc, "dayOfWeek", input.get("dayOfWeek"));
        putParsedIfTruthy(doc, "month", input.get("month"));
        putParsedIfTruthy(doc, "year", input.get("year"));
        return doc;
    }

    private Document updateSet(Map<String, Object> input) {
        Document set = new Document();
        if (input.containsKey("title")) set.put("title", trim(input.get("title")));
        if (input.containsKey("body")) set.put("body", trim(input.get("body")));
        if (input.containsKey("category")) set.put("category", input.get("category"));
        if (input.containsKey("timing")) set.put("timing", input.get("timing"));
        if (input.containsKey("dayOfMonth") && truthy(input.get("dayOfMonth"))) set.put("dayOfMonth", jsInt(input.get("dayOfMonth"), 0));
        if (input.containsKey("dayOfWeek") && truthy(input.get("dayOfWeek"))) set.put("dayOfWeek", jsInt(input.get("dayOfWeek"), 0));
        if (input.containsKey("month") && truthy(input.get("month"))) set.put("month", jsInt(input.get("month"), 0));
        if (input.containsKey("priority")) set.put("priority", jsInt(input.get("priority"), 0));
        if (input.containsKey("language")) set.put("language", input.get("language"));
        if (input.containsKey("targetAudience")) set.put("targetAudience", input.get("targetAudience"));
        if (input.containsKey("screen")) set.put("screen", trim(input.get("screen")));
        if (input.containsKey("data")) set.put("data", input.get("data"));
        if (input.containsKey("tags")) set.put("tags", trimmedList(input.get("tags")));
        if (input.containsKey("isActive")) set.put("isActive", input.get("isActive"));
        return set;
    }

    private Document updateUnset(Map<String, Object> input) {
        Document unset = new Document();
        if (input.containsKey("dayOfMonth") && !truthy(input.get("dayOfMonth"))) unset.put("dayOfMonth", "");
        if (input.containsKey("dayOfWeek") && !truthy(input.get("dayOfWeek"))) unset.put("dayOfWeek", "");
        if (input.containsKey("month") && !truthy(input.get("month"))) unset.put("month", "");
        return unset;
    }

    private Document schemaSet(Map<String, Object> input) {
        Document set = new Document();
        if (input.containsKey("title")) set.put("title", trim(input.get("title")));
        if (input.containsKey("body")) set.put("body", trim(input.get("body")));
        if (input.containsKey("category")) set.put("category", input.get("category"));
        if (input.containsKey("timing")) set.put("timing", input.get("timing"));
        if (input.containsKey("dayOfMonth")) set.put("dayOfMonth", jsInt(input.get("dayOfMonth"), 0));
        if (input.containsKey("dayOfWeek")) set.put("dayOfWeek", jsInt(input.get("dayOfWeek"), 0));
        if (input.containsKey("month")) set.put("month", jsInt(input.get("month"), 0));
        if (input.containsKey("year")) set.put("year", jsInt(input.get("year"), 0));
        if (input.containsKey("isActive")) set.put("isActive", bool(input.get("isActive")));
        if (input.containsKey("priority")) set.put("priority", jsInt(input.get("priority"), 0));
        if (input.containsKey("language")) set.put("language", input.get("language"));
        if (input.containsKey("targetAudience")) set.put("targetAudience", input.get("targetAudience"));
        if (input.containsKey("screen")) set.put("screen", trim(input.get("screen")));
        if (input.containsKey("data")) set.put("data", input.get("data"));
        if (input.containsKey("tags")) set.put("tags", trimmedList(input.get("tags")));
        if (input.containsKey("usageCount")) set.put("usageCount", jsInt(input.get("usageCount"), 0));
        if (input.containsKey("lastUsed")) set.put("lastUsed", input.get("lastUsed"));
        if (input.containsKey("createdBy")) set.put("createdBy", support.id(input.get("createdBy")));
        return set;
    }

    private MongoCollection<Document> collection() {
        return mongo.getCollection(Collections.NOTIFICATION_MESSAGES);
    }

    private Map<String, Object> data(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", value);
        return result;
    }

    private Document withId(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private void requireValidId(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("Invalid message ID");
    }

    private Page page(Map<String, String> query) {
        int rawPage = jsInt(query == null ? null : query.get("page"), 1);
        int rawLimit = jsInt(query == null ? null : query.get("limit"), 20);
        return new Page(rawPage, rawLimit, (rawPage - 1) * rawLimit, jsInt(query == null ? null : query.get("limit"), 20));
    }

    private static int jsInt(Object value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(String.valueOf(value).trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static int jsIntOr(Object value, int fallback) {
        try {
            int parsed = jsInt(value, fallback);
            return parsed == 0 ? fallback : parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static boolean present(Object value) {
        return value != null && !String.valueOf(value).isEmpty();
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0d && !Double.isNaN(n.doubleValue());
        String text = String.valueOf(value);
        return !text.isEmpty() && !"false".equals(text);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String trim(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Object orBlank(Object value) {
        if (value == null) return "";
        if (value instanceof Number n && n.doubleValue() == 0.0d) return "";
        if (value instanceof Boolean b && !b) return "";
        return value;
    }

    private static String csv(Object value) {
        return "\"" + jsString(value) + "\"";
    }

    private static String jsString(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) return "[object Object]";
        return String.valueOf(value);
    }

    private static void putIfPresent(Document doc, String key, Object value) {
        if (value != null) doc.put(key, value);
    }

    private static void putParsedIfTruthy(Document doc, String key, Object value) {
        if (truthy(value)) doc.put(key, jsInt(value, 0));
    }

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return ((List<Object>) list).stream().map(String::valueOf).toList();
    }

    private static List<String> trimmedList(Object value) {
        return stringList(value).stream().map(String::trim).toList();
    }

    private record Page(int rawPage, int rawLimit, int skip, int limit) {}
}
