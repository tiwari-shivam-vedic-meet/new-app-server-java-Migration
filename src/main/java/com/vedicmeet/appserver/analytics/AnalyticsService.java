package com.vedicmeet.appserver.analytics;

import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ports the reachable behavior in Node {@code rest-apis/modules/analytics.js}. */
@Service
public class AnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private final MongoTemplate mongo;
    private final CleverTapClient cleverTap;

    public AnalyticsService(MongoTemplate mongo, CleverTapClient cleverTap) {
        this.mongo = mongo;
        this.cleverTap = cleverTap;
    }

    public Map<String, Object> logEvent(Map<String, Object> input, ClientContext client) {
        String eventName = text(input, "event_name");
        if (blank(eventName)) throw new IllegalArgumentException("event_name is required");
        Map<String, Object> params = map(input.get("event_params"));
        String userId = firstNonBlank(text(input, "userId"), text(params, "user_id"));
        String userType = firstNonBlank(client.userType(), text(params, "user_type"), "anonymous");
        String platform = firstNonBlank(text(input, "platform"), "mobile");
        Date clientTimestamp = date(text(input, "timestamp"));
        Document statuses = platformStatuses(map(input.get("platform_statuses")));

        Map<String, Object> eventData = new LinkedHashMap<>(params);
        eventData.put("platform", platform);
        eventData.put("timestamp", clientTimestamp.toInstant().toString());
        eventData.put("user_id", userId);
        eventData.put("user_type", userType);
        eventData.put("ip_address", client.ipAddress());
        eventData.put("user_agent", client.userAgent());
        bestEffortCleverTap(userId, eventName, eventData, statuses);

        Document row = baseRow(eventName, classify(eventName), userId, userType, params,
                platform, client, clientTimestamp);
        row.put("event_params", new Document(eventData));
        row.put("platform_statuses", statuses);
        row.put("device_info", document(input.get("device_info") != null
                ? input.get("device_info") : params.get("device_info")));
        boolean anySucceeded = statuses.values().stream()
                .filter(Document.class::isInstance).map(Document.class::cast)
                .anyMatch(s -> Boolean.TRUE.equals(s.getBoolean("success")));
        row.put("status", anySucceeded ? "success" : "failed");
        row.put("error_message", anySucceeded ? null : "All platforms failed");
        row.put("metadata", new Document("logged_from", "api"));
        applyInstallFields(row, params);
        try {
            collection().insertOne(row);
            status(statuses, "backend", true, null);
        } catch (RuntimeException failure) {
            status(statuses, "backend", false, failure.getMessage());
            log.warn("Analytics database write failed for {}", eventName, failure);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event_name", eventName);
        data.put("event_type", row.getString("event_type"));
        data.put("user_id", userId);
        data.put("user_type", userType);
        data.put("platform", platform);
        data.put("logged_at", new Date().toInstant().toString());
        data.put("platform_statuses", statuses);
        data.put("overall_status", row.getString("status"));
        long successCount = statuses.values().stream().filter(Document.class::isInstance)
                .map(Document.class::cast).filter(s -> Boolean.TRUE.equals(s.getBoolean("success"))).count();
        data.put("success_rate", successCount + "/" + statuses.size());
        return data;
    }

    public Map<String, Object> logRegistration(Map<String, Object> input, ClientContext client) {
        return logIdentityEvent(input, client, "user_registration", "Registration source logged successfully");
    }

    public Map<String, Object> logLogin(Map<String, Object> input, ClientContext client) {
        return logIdentityEvent(input, client, "user_login", "Login source logged successfully");
    }

    public Map<String, Object> summary(String start, String end, String source) {
        return Map.of("period", nullableMap("start", start, "end", end),
                "filters", nullableMap("source", source),
                "summary", Map.of("total_events", 0, "unique_users", 0, "sources", Map.of()));
    }

    public Map<String, Object> logBatch(Map<String, Object> input, ClientContext client) {
        Object raw = input.get("events");
        if (!(raw instanceof List<?> events) || events.isEmpty())
            throw new IllegalArgumentException("Events array is required and must not be empty");
        if (events.size() > 100) throw new IllegalArgumentException("Batch size cannot exceed 100 events");
        List<Document> rows = new ArrayList<>();
        for (Object value : events) {
            Map<String, Object> event = map(value);
            String name = text(event, "event_name");
            if (blank(name)) throw new IllegalArgumentException("event_name is required");
            Map<String, Object> params = map(event.get("event_params"));
            String userId = text(event, "userId");
            Date timestamp = date(text(event, "timestamp"));
            Document row = baseRow(name, batchType(name), userId, "anonymous", params,
                    firstNonBlank(text(event, "platform"), "mobile"), client, timestamp);
            row.put("platform_statuses", document(event.get("platform_statuses")));
            row.put("device_info", document(event.get("device_info")));
            row.put("refer_id", event.get("refer_id"));
            row.put("status", "success");
            applyInstallFields(row, params);
            if (List.of("app_launched", "screen_view").contains(name))
                bestEffortCleverTap(userId, name, params, new Document());
            rows.add(row);
        }
        collection().insertMany(rows);
        return Map.of("batch_size", events.size(), "inserted_count", rows.size(),
                "batch_timestamp", firstNonBlank(text(input, "batch_timestamp"), Instant.now().toString()));
    }

    public Map<String, Object> heartbeat(Map<String, Object> input, ClientContext client) {
        String timestamp = text(input, "timestamp");
        if (blank(timestamp)) throw new IllegalArgumentException("timestamp is required");
        String state = firstNonBlank(text(input, "app_state"), "active");
        String deviceId = firstNonBlank(text(input, "device_id"), client.deviceId());
        Document event = new Document("timestamp", date(timestamp)).append("app_state", state)
                .append("user_id", client.userId()).append("device_id", deviceId)
                .append("ip_address", client.ipAddress()).append("user_agent", client.userAgent());
        collection().insertOne(new Document("event_name", "app_heartbeat")
                .append("event_type", "app_activity").append("event_params", event)
                .append("user_id", client.userId()).append("platform", "mobile")
                .append("client_timestamp", date(timestamp))
                .append("device_info", deviceId == null ? null : new Document("device_id", deviceId))
                .append("status", "success").append("createdAt", new Date()).append("updatedAt", new Date()));
        return nullableMap("timestamp", timestamp, "app_state", state);
    }

    private Map<String, Object> logIdentityEvent(Map<String, Object> input, ClientContext client,
                                                  String eventName, String ignoredMessage) {
        String userId = text(input, "user_id");
        if (blank(userId)) throw new IllegalArgumentException("user_id is required");
        String source = firstNonBlank(text(input, "source"), "direct");
        Map<String, Object> eventData = new LinkedHashMap<>(input);
        eventData.put("user_id", userId);
        eventData.put("source", source);
        eventData.put("timestamp", Instant.now().toString());
        eventData.put("ip_address", client.ipAddress());
        eventData.put("user_agent", client.userAgent());
        bestEffortCleverTap(userId, eventName, eventData, new Document());
        Document row = baseRow(eventName, eventName, userId, "user", input,
                "mobile", client, new Date());
        row.put("source", source);
        row.put("event_params", new Document(eventData));
        row.put("status", "success");
        row.put("metadata", new Document("logged_from",
                "user_registration".equals(eventName) ? "registration_api" : "login_api"));
        try { collection().insertOne(row); }
        catch (RuntimeException failure) { log.warn("Analytics identity write failed", failure); }
        return Map.of("user_id", userId, "source", source,
                "logged_at", String.valueOf(eventData.get("timestamp")));
    }

    private Document baseRow(String name, String type, String userId, String userType,
                             Map<String, Object> params, String platform, ClientContext client, Date timestamp) {
        return new Document("event_name", name).append("event_type", type)
                .append("user_id", userId).append("user_type", userType)
                .append("source", params.get("source")).append("utm_source", params.get("utm_source"))
                .append("utm_medium", params.get("utm_medium")).append("utm_campaign", params.get("utm_campaign"))
                .append("utm_term", params.get("utm_term")).append("utm_content", params.get("utm_content"))
                .append("event_params", new Document(params)).append("platform", platform)
                .append("ip_address", client.ipAddress()).append("user_agent", client.userAgent())
                .append("client_timestamp", timestamp).append("createdAt", new Date()).append("updatedAt", new Date());
    }

    private void applyInstallFields(Document row, Map<String, Object> params) {
        String type = row.getString("event_type");
        Date now = new Date();
        if ("app_install".equals(type)) {
            boolean first = Boolean.TRUE.equals(params.get("is_first_install"));
            row.put("install_status", first ? "installed" : "reinstalled");
            if (first) row.put("first_install_timestamp", now);
            row.put("last_install_timestamp", now);
            row.put("install_count", params.getOrDefault("install_count", 1));
        } else if ("app_uninstall".equals(type)) {
            row.put("install_status", "uninstalled");
            row.put("uninstall_timestamp", now);
        }
    }

    private void bestEffortCleverTap(String userId, String event, Map<String, Object> data, Document statuses) {
        if (blank(userId) || !cleverTap.isReady()) return;
        try {
            cleverTap.uploadEvent(userId, event, data);
            if (statuses.containsKey("clevertap")) status(statuses, "clevertap", true, null);
        } catch (RuntimeException failure) {
            if (statuses.containsKey("clevertap")) status(statuses, "clevertap", false, failure.getMessage());
            log.warn("Optional CleverTap event failed: {}", event, failure);
        }
    }

    private Document platformStatuses(Map<String, Object> raw) {
        Document result = new Document();
        for (String key : List.of("clevertap", "firebase", "meta", "backend")) {
            Map<String, Object> item = map(raw.get(key));
            boolean success = Boolean.TRUE.equals(item.get("success"));
            result.put(key, new Document("success", success).append("error", item.get("error"))
                    .append("logged_at", success ? new Date() : null));
        }
        return result;
    }

    private void status(Document statuses, String name, boolean success, String error) {
        statuses.put(name, new Document("success", success).append("error", error)
                .append("logged_at", success ? new Date() : null));
    }

    private String classify(String name) {
        String value = name.toLowerCase(Locale.ENGLISH);
        if (value.contains("registration") || value.contains("register")) return "user_registration";
        if (value.contains("login")) return "user_login";
        if (value.contains("screen") || value.contains("view")) return "screen_view";
        if (value.contains("purchase") || value.contains("payment")) return "purchase";
        if (value.contains("launch")) return "app_launch";
        if (value.contains("uninstall")) return "app_uninstall";
        if (value.contains("install")) return "app_install";
        if (value.contains("deep_link")) return "deep_link";
        if (value.contains("click") || value.contains("action")) return "user_action";
        return "other";
    }

    private String batchType(String name) {
        return switch (name) {
            case "app_install", "app_uninstall", "paid_consultation", "checkout_initiate" -> name;
            case "signup", "user_identified" -> "signup";
            case "purchase", "wallet_recharge", "in_app_purchase" -> "purchase";
            case "free_consultation", "start_trial" -> "free_consultation";
            case "app_launched" -> "app_launch";
            case "screen_view" -> "screen_view";
            default -> "custom";
        };
    }

    private MongoCollection<Document> collection() { return mongo.getCollection(Collections.ANALYTICS_LOGS); }
    private Date date(String value) {
        try { return blank(value) ? new Date() : Date.from(Instant.parse(value)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid timestamp"); }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> out = new LinkedHashMap<>();
            source.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }
    private Document document(Object value) { return new Document(map(value)); }
    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key); return value == null ? null : String.valueOf(value);
    }
    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value; return null;
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private Map<String, Object> nullableMap(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) out.put(String.valueOf(entries[i]), entries[i + 1]);
        return out;
    }

    public record ClientContext(String userId, String userType, String ipAddress,
                                String userAgent, String deviceId) { }
}
