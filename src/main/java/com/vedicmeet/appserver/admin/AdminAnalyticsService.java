package com.vedicmeet.appserver.admin;

import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithful port of Node rest-apis/modules/admin/analytics.js (+ the analyticsLog model
 * statics getSummary/getEventsBySource/getEventsByType and utils/functions/utc-date-helper).
 *
 * FAITHFUL(node-quirk): the "UTC" date helpers actually operate in Asia/Kolkata
 * (utc-date-helper.js:1-6 keeps the UTC names for backward compatibility).
 */
@Service
public class AdminAnalyticsService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ISO_MS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern INT_PREFIX = Pattern.compile("^[+-]?\\d+");

    public AdminAnalyticsService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    private MongoCollection<Document> coll() {
        return mongo.getCollection(Collections.ANALYTICS_LOGS);
    }

    // ── GET /admin/analytics/logs ────────────────────────────────────────
    public Map<String, Object> logs(Map<String, String> q) {
        int pageNum = jsParseInt(q.get("page"), 1);
        int limitNum = jsParseInt(q.get("limit"), 50);
        int skip = (pageNum - 1) * limitNum;

        Document query = new Document();
        applyDateFilter(query, q);

        if (truthy(q.get("source"))) query.append("source", q.get("source"));
        if (truthy(q.get("event_type"))) query.append("event_type", q.get("event_type"));
        if (truthy(q.get("platform"))) query.append("platform", q.get("platform"));
        if (truthy(q.get("user_id"))) query.append("user_id", q.get("user_id"));
        // FAITHFUL(node-quirk): analytics.js:82 uses the raw value as $regex (not escaped).
        if (truthy(q.get("event_name")))
            query.append("event_name", new Document("$regex", q.get("event_name")).append("$options", "i"));
        if (truthy(q.get("device_type"))) query.append("device_info.device_type", q.get("device_type"));

        String hasUserId = q.get("has_user_id");
        if ("true".equals(hasUserId)) {
            query.append("user_id", new Document("$exists", true).append("$ne", null));
        } else if ("false".equals(hasUserId)) {
            query.append("$or", Arrays.asList(
                    new Document("user_id", new Document("$exists", false)),
                    new Document("user_id", null)));
        }
        if (truthy(q.get("device_id"))) query.append("device_info.device_id", q.get("device_id"));

        String sortBy = orDefault(q.get("sort_by"), "createdAt");
        String sortOrder = orDefault(q.get("sort_order"), "desc");
        Document sort = new Document(sortBy, "asc".equals(sortOrder) ? 1 : -1);

        List<Document> logs = coll().find(query).sort(sort).skip(skip).limit(limitNum).into(new ArrayList<>());
        long total = coll().countDocuments(query);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logs", logs);
        data.put("pagination", pagination(pageNum, limitNum, total));
        return data;
    }

    // ── GET /admin/analytics/summary ─────────────────────────────────────
    public Map<String, Object> summary(Map<String, String> q) {
        String startDate = q.get("start_date");
        String endDate = q.get("end_date");
        String timeRange = q.get("timeRange");
        String tStart = q.get("timeRangeStartDate");
        String tEnd = q.get("timeRangeEndDate");
        String source = q.get("source");
        String eventType = q.get("event_type");
        String platform = q.get("platform");

        Map<String, String> filters = new LinkedHashMap<>();
        if (truthy(timeRange) && !"custom".equals(timeRange)) {
            filters.put("timeRange", timeRange);
        } else if ("custom".equals(timeRange) && truthy(tStart) && truthy(tEnd)) {
            filters.put("timeRange", "custom");
            filters.put("timeRangeStartDate", tStart);
            filters.put("timeRangeEndDate", tEnd);
        } else {
            if (truthy(startDate)) filters.put("start_date", startDate);
            if (truthy(endDate)) filters.put("end_date", endDate);
        }
        if (truthy(source)) filters.put("source", source);
        if (truthy(eventType)) filters.put("event_type", eventType);
        if (truthy(platform)) filters.put("platform", platform);

        Document summary = getSummary(filters);
        List<Document> eventsBySource = getEventsBySource(filters);
        List<Document> eventsByType = getEventsByType(filters);

        Document dateFilter = new Document();
        applyDateFilter(dateFilter, q);

        Document matchFilter = new Document(dateFilter);
        if (truthy(source)) matchFilter.append("source", source);
        if (truthy(eventType)) matchFilter.append("event_type", eventType);
        if (truthy(platform)) matchFilter.append("platform", platform);

        List<Document> recentEvents = coll().find(matchFilter)
                .sort(new Document("createdAt", -1))
                .limit(10)
                .projection(new Document("event_name", 1).append("event_type", 1)
                        .append("source", 1).append("user_id", 1).append("createdAt", 1))
                .into(new ArrayList<>());

        long totalEvents = asLong(summary.get("total_events"));
        long totalEventsOr1 = totalEvents != 0 ? totalEvents : 1; // summary.total_events || 1
        List<Document> sourceDistribution = coll().aggregate(Arrays.asList(
                new Document("$match", matchFilter),
                new Document("$group", new Document("_id", "$source")
                        .append("count", new Document("$sum", 1))
                        .append("unique_users", new Document("$addToSet", "$user_id"))),
                new Document("$project", new Document("source", "$_id")
                        .append("count", 1)
                        .append("unique_users", new Document("$size", "$unique_users"))
                        .append("percentage", new Document("$multiply", Arrays.asList(
                                new Document("$divide", Arrays.asList("$count", totalEventsOr1)), 100)))),
                new Document("$sort", new Document("count", -1))
        )).into(new ArrayList<>());

        Map<String, Object> summaryOut = new LinkedHashMap<>();
        summaryOut.put("total_events", totalEvents); // summary.total_events || 0
        summaryOut.put("unique_users", asLong(summary.get("unique_users"))); // || 0

        Map<String, Object> filtersOut = new LinkedHashMap<>();
        filtersOut.put("timeRange", orNull(timeRange));
        filtersOut.put("timeRangeStartDate", orNull(tStart));
        filtersOut.put("timeRangeEndDate", orNull(tEnd));
        filtersOut.put("start_date", orNull(startDate));
        filtersOut.put("end_date", orNull(endDate));
        filtersOut.put("source", orNull(source));
        filtersOut.put("event_type", orNull(eventType));
        filtersOut.put("platform", orNull(platform));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summaryOut);
        data.put("events_by_source", eventsBySource);
        data.put("events_by_type", eventsByType);
        data.put("source_distribution", sourceDistribution);
        data.put("recent_events", recentEvents);
        data.put("filters", filtersOut);
        return data;
    }

    // ── GET /admin/analytics/logs/:id ────────────────────────────────────
    public Map<String, Object> log(String id) {
        Document log = coll().find(new Document("_id", support.id(id))).first();
        // FAITHFUL: analytics.js:305 — not found -> {code:404,'Analytics log not found'}
        // (the in-body 404 collapses to executeCodeData's 500; message preserved).
        if (log == null) throw new IllegalStateException("Analytics log not found");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("log", log);
        return data;
    }

    // ── GET /admin/analytics/unique-devices ──────────────────────────────
    public Map<String, Object> uniqueDevices(Map<String, String> q) {
        Document matchStage = new Document();
        matchStage.append("$or", Arrays.asList(
                new Document("user_id", new Document("$exists", false)),
                new Document("user_id", null)));
        applyDateFilter(matchStage, q);
        if (truthy(q.get("source"))) matchStage.append("source", q.get("source"));
        if (truthy(q.get("event_type"))) matchStage.append("event_type", q.get("event_type"));
        if (truthy(q.get("platform"))) matchStage.append("platform", q.get("platform"));
        if (truthy(q.get("device_type"))) matchStage.append("device_info.device_type", q.get("device_type"));
        // FAITHFUL(node-quirk): analytics.js duplicate $ne key in the object literal — the
        // last one wins, so the effective filter is {$exists:true, $ne:''} ($ne:null is dropped).
        matchStage.append("device_info.device_id", new Document("$exists", true).append("$ne", ""));

        List<Document> result = coll().aggregate(Arrays.asList(
                new Document("$match", matchStage),
                new Document("$group", new Document("_id", "$device_info.device_id")
                        .append("total_events", new Document("$sum", 1))
                        .append("first_event", new Document("$min", "$createdAt"))
                        .append("last_event", new Document("$max", "$createdAt"))
                        .append("event_types", new Document("$addToSet", "$event_type"))
                        .append("sources", new Document("$addToSet", "$source"))
                        .append("device_type", new Document("$first", "$device_info.device_type"))
                        .append("device_model", new Document("$first", "$device_info.device_model"))
                        .append("app_version", new Document("$first", "$device_info.app_version"))),
                new Document("$project", new Document("device_id", "$_id")
                        .append("total_events", 1).append("first_event", 1).append("last_event", 1)
                        .append("event_types", 1).append("sources", 1).append("device_type", 1)
                        .append("device_model", 1).append("app_version", 1)),
                new Document("$sort", new Document("total_events", -1))
        )).into(new ArrayList<>());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("devices", result);
        data.put("total_devices", result.size());
        return data;
    }

    // ── GET /admin/analytics/unique-users ────────────────────────────────
    public Map<String, Object> uniqueUsers(Map<String, String> q) {
        Document matchStage = new Document();
        matchStage.append("user_id", new Document("$exists", true).append("$ne", null));
        applyDateFilter(matchStage, q);
        if (truthy(q.get("source"))) matchStage.append("source", q.get("source"));
        if (truthy(q.get("event_type"))) matchStage.append("event_type", q.get("event_type"));
        if (truthy(q.get("platform"))) matchStage.append("platform", q.get("platform"));
        if (truthy(q.get("device_type"))) matchStage.append("device_info.device_type", q.get("device_type"));

        List<Document> result = coll().aggregate(Arrays.asList(
                new Document("$match", matchStage),
                new Document("$group", new Document("_id", "$user_id")
                        .append("total_events", new Document("$sum", 1))
                        .append("first_event", new Document("$min", "$createdAt"))
                        .append("last_event", new Document("$max", "$createdAt"))
                        .append("event_types", new Document("$addToSet", "$event_type"))
                        .append("sources", new Document("$addToSet", "$source"))),
                new Document("$project", new Document("user_id", "$_id")
                        .append("total_events", 1).append("first_event", 1).append("last_event", 1)
                        .append("event_types", 1).append("sources", 1)),
                new Document("$sort", new Document("total_events", -1))
        )).into(new ArrayList<>());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", result);
        data.put("total_users", result.size());
        return data;
    }

    // ── GET /admin/analytics/device/:device_id ───────────────────────────
    public Map<String, Object> device(String deviceId, Map<String, String> q) {
        int pageNum = jsParseInt(q.get("page"), 1);
        int limitNum = jsParseInt(q.get("limit"), 50);
        int skip = (pageNum - 1) * limitNum;

        Document query = new Document("device_info.device_id", deviceId)
                .append("$or", Arrays.asList(
                        new Document("user_id", new Document("$exists", false)),
                        new Document("user_id", null)));
        applyDateFilter(query, q);

        List<Document> logs = coll().find(query).sort(new Document("createdAt", -1))
                .skip(skip).limit(limitNum).into(new ArrayList<>());
        long total = coll().countDocuments(query);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device_id", deviceId);
        data.put("logs", logs);
        data.put("pagination", pagination(pageNum, limitNum, total));
        return data;
    }

    // ── GET /admin/analytics/user/:user_id ───────────────────────────────
    public Map<String, Object> user(String userId, Map<String, String> q) {
        int pageNum = jsParseInt(q.get("page"), 1);
        int limitNum = jsParseInt(q.get("limit"), 50);
        int skip = (pageNum - 1) * limitNum;

        Document query = new Document("user_id", userId);
        applyDateFilter(query, q);

        List<Document> logs = coll().find(query).sort(new Document("createdAt", -1))
                .skip(skip).limit(limitNum).into(new ArrayList<>());
        long total = coll().countDocuments(query);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", userId);
        data.put("logs", logs);
        data.put("pagination", pagination(pageNum, limitNum, total));
        return data;
    }

    // ── GET /admin/analytics/export (CSV) ────────────────────────────────
    public String export(Map<String, String> q) {
        Document query = new Document();
        applyDateFilter(query, q);
        if (truthy(q.get("source"))) query.append("source", q.get("source"));
        if (truthy(q.get("event_type"))) query.append("event_type", q.get("event_type"));
        if (truthy(q.get("platform"))) query.append("platform", q.get("platform"));

        List<Document> logs = coll().find(query).sort(new Document("createdAt", -1)).into(new ArrayList<>());

        String[] headers = {
                "Event Name", "Event Type", "User ID", "Source", "UTM Source", "UTM Medium",
                "UTM Campaign", "Platform", "Device Type", "Device Model", "Device Manufacturer",
                "OS Version", "App Version", "App Build", "Device ID", "Screen Resolution",
                "Is Jailbroken", "Carrier", "Network Type", "Install Status",
                "First Install Timestamp", "Last Install Timestamp", "Uninstall Timestamp",
                "Install Count", "IP Address", "Created At"
        };

        StringBuilder csv = new StringBuilder(String.join(",", headers));
        for (Document log : logs) {
            Document di = log.get("device_info") instanceof Document d ? d : new Document();
            Object sr = di.get("screen_resolution");
            String screen = truthy(sr) && sr instanceof Document r
                    ? String.valueOf(r.get("width")) + "x" + String.valueOf(r.get("height")) : "";
            String[] cells = {
                    orEmpty(log.get("event_name")),
                    orEmpty(log.get("event_type")),
                    orEmpty(log.get("user_id")),
                    orEmpty(log.get("source")),
                    orEmpty(log.get("utm_source")),
                    orEmpty(log.get("utm_medium")),
                    orEmpty(log.get("utm_campaign")),
                    orEmpty(log.get("platform")),
                    orEmpty(di.get("device_type")),
                    orEmpty(di.get("device_model")),
                    orEmpty(di.get("device_manufacturer")),
                    orEmpty(di.get("os_version")),
                    orEmpty(di.get("app_version")),
                    orEmpty(di.get("app_build")),
                    orEmpty(di.get("device_id")),
                    screen,
                    truthy(di.get("is_jailbroken")) ? "Yes" : "No",
                    orEmpty(di.get("carrier")),
                    orEmpty(di.get("network_type")),
                    orEmpty(log.get("install_status")),
                    isoOf(log.get("first_install_timestamp")),
                    isoOf(log.get("last_install_timestamp")),
                    isoOf(log.get("uninstall_timestamp")),
                    truthy(log.get("install_count")) ? String.valueOf(log.get("install_count")) : "0",
                    orEmpty(log.get("ip_address")),
                    isoOf(log.get("createdAt"))
            };
            csv.append('\n');
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) csv.append(',');
                csv.append('"').append(cells[i].replace("\"", "\"\"")).append('"');
            }
        }
        return csv.toString();
    }

    // ── analyticsLog model statics ───────────────────────────────────────
    Document getSummary(Map<String, String> filters) {
        Document matchStage = new Document();
        applyDateFilter(matchStage, filters);
        if (truthy(filters.get("source"))) matchStage.append("source", filters.get("source"));
        if (truthy(filters.get("event_type"))) matchStage.append("event_type", filters.get("event_type"));
        if (truthy(filters.get("platform"))) matchStage.append("platform", filters.get("platform"));

        List<Document> result = coll().aggregate(Arrays.asList(
                new Document("$match", matchStage),
                new Document("$group", new Document("_id", null)
                        .append("total_events", new Document("$sum", 1))
                        .append("unique_users", new Document("$addToSet", "$user_id"))
                        .append("sources", new Document("$push", "$source"))
                        .append("event_types", new Document("$push", "$event_type"))),
                new Document("$project", new Document("total_events", 1)
                        .append("unique_users", new Document("$size", "$unique_users"))
                        .append("sources", 1).append("event_types", 1))
        )).into(new ArrayList<>());
        if (!result.isEmpty()) return result.get(0);
        return new Document("total_events", 0).append("unique_users", 0)
                .append("sources", new ArrayList<>()).append("event_types", new ArrayList<>());
    }

    List<Document> getEventsBySource(Map<String, String> filters) {
        Document matchStage = new Document();
        applyDateFilter(matchStage, filters);
        return coll().aggregate(Arrays.asList(
                new Document("$match", matchStage),
                new Document("$group", new Document("_id", "$source")
                        .append("count", new Document("$sum", 1))
                        .append("unique_users", new Document("$addToSet", "$user_id"))),
                new Document("$project", new Document("source", "$_id").append("count", 1)
                        .append("unique_users", new Document("$size", "$unique_users"))),
                new Document("$sort", new Document("count", -1))
        )).into(new ArrayList<>());
    }

    List<Document> getEventsByType(Map<String, String> filters) {
        Document matchStage = new Document();
        applyDateFilter(matchStage, filters);
        return coll().aggregate(Arrays.asList(
                new Document("$match", matchStage),
                new Document("$group", new Document("_id", "$event_type")
                        .append("count", new Document("$sum", 1))
                        .append("unique_users", new Document("$addToSet", "$user_id"))),
                new Document("$project", new Document("event_type", "$_id").append("count", 1)
                        .append("unique_users", new Document("$size", "$unique_users"))),
                new Document("$sort", new Document("count", -1))
        )).into(new ArrayList<>());
    }

    // ── date filter shared by routes + statics (analytics.js + model) ────
    private void applyDateFilter(Document target, Map<String, String> src) {
        String timeRange = src.get("timeRange");
        String tStart = src.get("timeRangeStartDate");
        String tEnd = src.get("timeRangeEndDate");
        String startDate = src.get("start_date");
        String endDate = src.get("end_date");

        if (truthy(timeRange) && !"custom".equals(timeRange)) {
            try {
                target.append("createdAt", getUTCDateRangeQuery(timeRange));
            } catch (RuntimeException ignored) { /* Node logs & skips invalid timeRange */ }
        } else if ("custom".equals(timeRange) && truthy(tStart) && truthy(tEnd)) {
            try {
                Date[] range = getUTCDateRange("custom", tStart, tEnd);
                target.append("createdAt", new Document("$gte", range[0]).append("$lte", range[1]));
            } catch (RuntimeException ignored) { /* Node logs & skips */ }
        } else if (truthy(startDate) || truthy(endDate)) {
            Document createdAt = new Document();
            if (truthy(startDate)) createdAt.append("$gte", jsDate(startDate));
            if (truthy(endDate)) createdAt.append("$lte", jsDate(endDate));
            target.append("createdAt", createdAt);
        }
    }

    // ── utc-date-helper.js (Asia/Kolkata) ────────────────────────────────
    private static Document getUTCDateRangeQuery(String timeRange) {
        Date[] range = getUTCDateRange(timeRange, null, null);
        return new Document("$gte", range[0]).append("$lte", range[1]);
    }

    private static Date[] getUTCDateRange(String timeRange, String startDate, String endDate) {
        ZonedDateTime now = ZonedDateTime.now(KOLKATA);
        return getUTCDateRange(timeRange, startDate, endDate, now);
    }

    /** Package-private + clock-injectable so the Asia/Kolkata quirks are unit-testable. */
    static Date[] getUTCDateRange(String timeRange, String startDate, String endDate, ZonedDateTime now) {
        Date start;
        Date end;
        switch (timeRange) {
            case "today" -> { start = startOfDay(now); end = endOfDay(now); }
            case "yesterday" -> {
                ZonedDateTime y = now.minusDays(1);
                start = startOfDay(y); end = endOfDay(y);
            }
            case "last7days" -> { start = startOfDay(now.minusDays(7)); end = endOfDay(now); }
            case "last30days" -> { start = startOfDay(now.minusDays(30)); end = endOfDay(now); }
            case "last60days" -> { start = startOfDay(now.minusDays(60)); end = endOfDay(now); }
            case "thisMonth" -> { start = startOfMonth(now); end = endOfDay(now); }
            case "lastMonth" -> {
                ZonedDateTime lm = now.minusMonths(1);
                start = startOfMonth(lm); end = endOfMonth(lm);
            }
            case "custom" -> {
                if (truthy(startDate) && truthy(endDate)) {
                    start = startOfDay(LocalDate.parse(startDate).atStartOfDay(KOLKATA));
                    end = endOfDay(LocalDate.parse(endDate).atStartOfDay(KOLKATA));
                } else {
                    throw new IllegalArgumentException("Custom date range requires both startDate and endDate");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported timeRange: " + timeRange);
        }
        return new Date[]{start, end};
    }

    private static Date startOfDay(ZonedDateTime z) {
        return Date.from(z.toLocalDate().atStartOfDay(KOLKATA).toInstant());
    }

    private static Date endOfDay(ZonedDateTime z) {
        return Date.from(z.toLocalDate().atTime(23, 59, 59, 999_000_000).atZone(KOLKATA).toInstant());
    }

    private static Date startOfMonth(ZonedDateTime z) {
        return Date.from(z.toLocalDate().withDayOfMonth(1).atStartOfDay(KOLKATA).toInstant());
    }

    private static Date endOfMonth(ZonedDateTime z) {
        LocalDate last = z.toLocalDate().withDayOfMonth(z.toLocalDate().lengthOfMonth());
        return Date.from(last.atTime(23, 59, 59, 999_000_000).atZone(KOLKATA).toInstant());
    }

    // ── JS-semantics helpers ─────────────────────────────────────────────
    private static Map<String, Object> pagination(int page, int limit, long total) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("page", page);
        p.put("limit", limit);
        p.put("total", total);
        p.put("total_pages", (long) Math.ceil((double) total / limit));
        return p;
    }

    /** new Date(str): ISO instant / offset / date-only (UTC midnight). */
    private static Date jsDate(String s) {
        try { return Date.from(Instant.parse(s)); } catch (RuntimeException ignored) { /* try next */ }
        try { return Date.from(OffsetDateTime.parse(s).toInstant()); } catch (RuntimeException ignored) { /* try next */ }
        try { return Date.from(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()); }
        catch (RuntimeException ignored) { throw new IllegalArgumentException("Invalid date: " + s); }
    }

    private static String isoOf(Object v) {
        if (!truthy(v)) return "";
        Instant instant;
        if (v instanceof Date d) instant = d.toInstant();
        else if (v instanceof Number n) instant = Instant.ofEpochMilli(n.longValue());
        else {
            try { instant = jsDate(String.valueOf(v)).toInstant(); }
            catch (RuntimeException ignored) { return ""; }
        }
        return ISO_MS.format(instant);
    }

    private static int jsParseInt(String value, int fallback) {
        if (value == null) return fallback; // destructuring default only applies when key is absent
        Matcher matcher = INT_PREFIX.matcher(value.trim());
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group()); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value; // destructuring default only when key absent
    }

    private static Object orNull(String value) {
        return truthy(value) ? value : null; // Node `x || null`
    }

    private static String orEmpty(Object value) {
        return truthy(value) ? String.valueOf(value) : ""; // Node `x || ''`
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) return !string.isEmpty();
        if (value instanceof Number number) return number.doubleValue() != 0;
        return true;
    }
}
