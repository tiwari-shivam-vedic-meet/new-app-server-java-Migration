package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Faithful port of Node rest-apis/modules/admin/count-tracking.js. */
@Service
public class AdminCountTrackingService {

    private static final List<String> TRACKED_TYPES = List.of(
            "cancelled_consultation",
            "waitlist_management",
            "missed_call",
            "progress_call",
            "completed_call",
            "support_management",
            "booked_session");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminCountTrackingService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> dashboard(String adminId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unreadCounts", unreadCounts(adminId));
        result.put("totalEntries", mongo.getCollection(Collections.ENTRY_TRACKINGS)
                .countDocuments(new Document("adminId", support.id(adminId))));
        result.put("lastUpdated", new Date());
        return result;
    }

    public Map<String, Object> unreadCounts(String adminId) {
        if (adminId == null || adminId.isBlank()) {
            throw new IllegalArgumentException("Admin ID not found in request");
        }
        try {
            List<Document> counts = aggregate(unreadCountPipeline(adminId));
            Map<String, Object> result = zeroCounts();
            for (Document item : counts) {
                Object id = item.get("_id");
                if (id != null && result.containsKey(String.valueOf(id))) {
                    result.put(String.valueOf(id), item.get("count"));
                }
            }
            return result;
        } catch (Exception ignored) {
            // FAITHFUL(node-quirk): utils/functions/count-tracking-service.js:164 returns all-zero counts on any error.
            return zeroCounts();
        }
    }

    public Map<String, Object> trackSupport(String adminId, String supportId) {
        if (adminId == null || adminId.isBlank()) {
            throw new IllegalArgumentException("Admin ID not found in request");
        }
        Document supportEntry = mongo.getCollection(Collections.CUSTOMER_SUPPORT_QUERIES)
                .find(new Document("_id", support.id(supportId))).first();
        if (supportEntry == null) {
            throw new IllegalStateException("Support entry not found");
        }

        // FAITHFUL(node-quirk): rest-apis/modules/admin/count-tracking.js:99-103 helper swallows tracking failures.
        try {
            trackSupportEntry(supportEntry, adminId, "created");
        } catch (Exception ignored) {
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supportId", supportEntry.get("_id"));
        result.put("title", supportEntry.get("title"));
        result.put("status", supportEntry.get("status"));
        return result;
    }

    public Map<String, Object> entries(String adminId, String type, Map<String, String> query) {
        Options options = options(query);
        Document params = new Document("adminId", support.id(adminId)).append("type", type);
        if (present(options.status)) params.put("status", options.status);
        if (present(options.priority)) params.put("priority", options.priority);
        if (options.isRead != null) params.put("readStatus.isRead", options.isRead);

        List<Map<String, Object>> rows = aggregate(entriesPipeline(params, options.skip(), options.limit,
                options.sortBy, options.sortOrder)).stream().map(this::withVirtuals).toList();
        long total = mongo.getCollection(Collections.ENTRY_TRACKINGS).countDocuments(params);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", options.page);
        pagination.put("limit", options.limit);
        pagination.put("total", total);
        pagination.put("pages", (long) Math.ceil((double) total / options.limit));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", rows);
        result.put("pagination", pagination);
        return result;
    }

    public Map<String, Object> markRead(String adminId, Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document params = new Document("adminId", support.id(adminId))
                .append("readStatus.isRead", false);
        List<?> entryIds = list(input.get("entryIds"));
        if (entryIds != null && !entryIds.isEmpty()) {
            params.put("_id", new Document("$in", entryIds.stream().map(support::id).toList()));
        }
        if (present(input.get("type"))) {
            params.put("type", input.get("type"));
        }

        UpdateResult update = mongo.getCollection(Collections.ENTRY_TRACKINGS).updateMany(params,
                new Document("$set", new Document("readStatus.isRead", true)
                        .append("readStatus.readAt", new Date())
                        .append("readStatus.readBy", support.id(adminId))
                        .append("updatedAt", new Date())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modifiedCount", update.getModifiedCount());
        return result;
    }

    public Map<String, Object> trackEntry(String adminId, Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Object type = input.get("type");
        Object entryId = input.get("entryId");
        Object reference = input.get("reference");
        if (!present(type) || !present(entryId) || !present(adminId) || !present(reference)) {
            throw new IllegalArgumentException("Missing required fields: type, entryId, adminId, reference");
        }

        long currentCount = getCountByType(String.valueOf(type), adminId);
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId())
                .append("type", String.valueOf(type))
                .append("entryId", String.valueOf(entryId))
                .append("adminId", support.id(adminId))
                .append("reference", support.id(reference))
                .append("status", str(input.getOrDefault("status", "new")))
                .append("priority", str(input.getOrDefault("priority", "medium")))
                .append("source", str(input.getOrDefault("source", "admin_action")))
                .append("metadata", document(input.getOrDefault("metadata", Map.of())))
                .append("readStatus", new Document("isRead", false).append("readAt", null).append("readBy", null))
                .append("countChange", new Document("previousCount", currentCount)
                        .append("currentCount", currentCount + 1)
                        .append("changeType", "increment"))
                .append("tags", new ArrayList<>())
                .append("expiresAt", null)
                .append("createdAt", now)
                .append("updatedAt", now);
        mongo.getCollection(Collections.ENTRY_TRACKINGS).insertOne(doc);
        return withVirtuals(doc);
    }

    public Map<String, Object> updateStatus(String adminId, String entryId, Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document entry = findEntryById(entryId);
        if (entry == null) throw new IllegalStateException("Entry not found");

        Document metadata = document(entry.getOrDefault("metadata", Map.of()));
        metadata.putAll(document(input.getOrDefault("metadata", Map.of())));

        Document updated = mongo.getCollection(Collections.ENTRY_TRACKINGS).findOneAndUpdate(
                new Document("_id", support.id(entryId)),
                new Document("$set", new Document("status", input.get("status"))
                        .append("metadata", metadata)
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withVirtuals(updated == null ? entry : updated);
    }

    public Map<String, Object> updatePriority(String adminId, String entryId, Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document entry = findEntryById(entryId);
        if (entry == null) throw new IllegalStateException("Entry not found");

        Document updated = mongo.getCollection(Collections.ENTRY_TRACKINGS).findOneAndUpdate(
                new Document("_id", support.id(entryId)),
                new Document("$set", new Document("priority", input.get("priority"))
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withVirtuals(updated == null ? entry : updated);
    }

    public Map<String, Object> assign(String adminId, String entryId, Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document entry = findEntryById(entryId);
        if (entry == null) throw new IllegalStateException("Entry not found");

        // FAITHFUL(node-quirk): utils/functions/count-tracking-service.js:328-329 writes assignedTo inside Mixed metadata.
        Document updated = mongo.getCollection(Collections.ENTRY_TRACKINGS).findOneAndUpdate(
                new Document("_id", support.id(entryId)),
                new Document("$set", new Document("metadata.assignedTo", input.get("assignedTo"))
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withVirtuals(updated == null ? entry : updated);
    }

    public Map<String, Object> entry(String adminId, String entryId) {
        List<Document> rows = aggregate(entryPipeline(adminId, entryId));
        if (rows.isEmpty()) throw new IllegalStateException("Entry not found");
        return withVirtuals(rows.get(0));
    }

    public Map<String, Object> deleteEntry(String adminId, String entryId) {
        Document deleted = mongo.getCollection(Collections.ENTRY_TRACKINGS).findOneAndDelete(
                new Document("_id", support.id(entryId)).append("adminId", support.id(adminId)));
        if (deleted == null) {
            throw new IllegalStateException("Entry not found");
        }
        return new LinkedHashMap<>();
    }

    public Map<String, Object> stats(String adminId, Map<String, String> query) {
        Document match = new Document("adminId", support.id(adminId));
        if (query != null && present(query.get("startDate")) && present(query.get("endDate"))) {
            match.put("createdAt", new Document("$gte", date(query.get("startDate")))
                    .append("$lte", date(query.get("endDate"))));
        }

        long totalEntries = mongo.getCollection(Collections.ENTRY_TRACKINGS).countDocuments(match);
        Document unreadMatch = new Document(match).append("readStatus.isRead", false);
        long unreadEntries = mongo.getCollection(Collections.ENTRY_TRACKINGS).countDocuments(unreadMatch);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalEntries", totalEntries);
        result.put("unreadEntries", unreadEntries);
        result.put("readEntries", totalEntries - unreadEntries);
        result.put("entriesByType", aggregate(groupPipeline(match, "type")));
        result.put("entriesByStatus", aggregate(groupPipeline(match, "status")));
        result.put("entriesByPriority", aggregate(groupPipeline(match, "priority")));
        return result;
    }

    List<Document> unreadCountPipeline(String adminId) {
        return List.of(
                new Document("$match", new Document("adminId", support.id(adminId))
                        .append("readStatus.isRead", false)),
                Document.parse("{ $group: { _id: '$type', count: { $sum: 1 } } }"));
    }

    List<Document> entriesPipeline(Document params, int skip, int limit, String sortBy, int sortOrder) {
        return List.of(
                new Document("$match", params),
                populateAdminStage("adminId", "adminId"),
                Document.parse("{ $unwind: { path: '$adminId', preserveNullAndEmptyArrays: true } }"),
                new Document("$sort", new Document(sortBy, sortOrder)),
                new Document("$skip", skip),
                new Document("$limit", limit));
    }

    List<Document> entryPipeline(String adminId, String entryId) {
        return List.of(
                new Document("$match", new Document("_id", support.id(entryId)).append("adminId", support.id(adminId))),
                populateAdminStage("adminId", "adminId"),
                Document.parse("{ $unwind: { path: '$adminId', preserveNullAndEmptyArrays: true } }"),
                populateAdminStage("readStatus.readBy", "readStatus.readBy"),
                Document.parse("{ $unwind: { path: '$readStatus.readBy', preserveNullAndEmptyArrays: true } }"),
                // FAITHFUL(node-bug): rest-apis/modules/admin/count-tracking.js:345 populates non-schema readStatus.lastViewedBy.
                populateAdminStage("readStatus.lastViewedBy", "readStatus.lastViewedBy"),
                Document.parse("{ $unwind: { path: '$readStatus.lastViewedBy', preserveNullAndEmptyArrays: true } }"));
    }

    List<Document> groupPipeline(Document match, String field) {
        return List.of(
                new Document("$match", match),
                Document.parse("{ $group: { _id: '$" + field + "', count: { $sum: 1 } } }"));
    }

    private void trackSupportEntry(Document supportEntry, String adminId, String action) {
        String priority = switch (str(supportEntry.get("supportType"))) {
            case "TECHNICAL", "COINS" -> "high";
            case "LIVE" -> "medium";
            default -> "medium";
        };
        String status = "new";
        switch (str(supportEntry.get("status"))) {
            case "in_progress" -> status = "in_progress";
            case "completed" -> status = "resolved";
            case "escalated" -> {
                status = "new";
                priority = "urgent";
            }
            default -> status = "new";
        }

        Document metadata = new Document("supportId", supportEntry.get("_id"))
                .append("consultantId", supportEntry.get("consultantId"))
                .append("userId", supportEntry.get("userId"))
                .append("supportType", supportEntry.get("supportType"))
                .append("userType", supportEntry.get("userType"))
                .append("title", supportEntry.get("title"))
                .append("ticketNumber", supportEntry.get("ticketNumber"))
                .append("isResolved", supportEntry.get("isResolved"))
                .append("isAdminReply", supportEntry.get("isAdminReply"))
                .append("threadId", supportEntry.get("threadId"))
                .append("action", action);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "support_management");
        body.put("entryId", String.valueOf(supportEntry.get("_id")));
        body.put("reference", supportEntry.get("_id"));
        body.put("metadata", metadata);
        body.put("status", status);
        body.put("priority", priority);
        body.put("source", "system");
        trackEntry(adminId, body);
    }

    private long getCountByType(String type, String adminId) {
        try {
            return mongo.getCollection(Collections.ENTRY_TRACKINGS).countDocuments(new Document("type", type)
                    .append("adminId", support.id(adminId))
                    .append("readStatus.isRead", false));
        } catch (Exception ignored) {
            // FAITHFUL(node-quirk): utils/functions/count-tracking-service.js:88-90 getCountByType returns 0 on error.
            return 0;
        }
    }

    private Document findEntryById(String entryId) {
        return mongo.getCollection(Collections.ENTRY_TRACKINGS)
                .find(new Document("_id", support.id(entryId))).first();
    }

    private List<Document> aggregate(List<Document> pipeline) {
        AggregateIterable<Document> iterable = mongo.getCollection(Collections.ENTRY_TRACKINGS).aggregate(pipeline);
        return iterable.into(new ArrayList<>());
    }

    private Document populateAdminStage(String localField, String as) {
        return new Document("$lookup", new Document("from", Collections.ADMINS)
                .append("localField", localField)
                .append("foreignField", "_id")
                .append("pipeline", List.of(Document.parse("{ $project: { name: 1, email: 1 } }")))
                .append("as", as));
    }

    private Map<String, Object> zeroCounts() {
        Map<String, Object> result = new LinkedHashMap<>();
        TRACKED_TYPES.forEach(type -> result.put(type, 0));
        return result;
    }

    private Map<String, Object> withVirtuals(Document doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (doc != null) out.putAll(doc);
        Object id = out.get("_id");
        if (id instanceof ObjectId objectId) {
            out.put("id", objectId.toHexString());
        } else if (id != null) {
            out.put("id", String.valueOf(id));
        }
        out.put("isUnread", isUnread(out.get("readStatus")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Document document(Object value) {
        if (value instanceof Document document) return new Document(document);
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }

    @SuppressWarnings("unchecked")
    private List<?> list(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private boolean isUnread(Object readStatus) {
        if (readStatus instanceof Document document) {
            return !Boolean.TRUE.equals(document.get("isRead"));
        }
        if (readStatus instanceof Map<?, ?> map) {
            return !Boolean.TRUE.equals(((Map<String, Object>) map).get("isRead"));
        }
        return true;
    }

    private Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }

    private boolean present(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Date date(String value) {
        try {
            return Date.from(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            return Date.from(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
    }

    private Options options(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        int page = parseInt(q.get("page"), 1);
        int limit = parseInt(q.get("limit"), 20);
        String isRead = q.get("isRead");
        return new Options(page, limit, q.get("status"), q.get("priority"),
                isRead == null ? null : "true".equals(isRead),
                q.getOrDefault("sortBy", "createdAt"),
                parseInt(q.get("sortOrder"), -1));
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record Options(int page, int limit, String status, String priority, Boolean isRead,
                           String sortBy, int sortOrder) {
        int skip() { return (page - 1) * limit; }
    }
}
