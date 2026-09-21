package com.vedicmeet.appserver.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ports Node click attribution and deferred deep-link behavior from tracking.js/helper. */
@Service
public class TrackingService {
    private static final List<String> TRACKING_FIELDS = List.of(
            "source", "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content");
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final String websiteInstallUrl;
    private final String appStoreUrl;
    private final String appScheme;
    private final String androidPackage;
    private final String iosAppId;
    private final int matchingWindowDays;
    private final int expirationDays;

    public TrackingService(MongoTemplate mongo, ObjectMapper mapper,
            @Value("${vedicmeet.tracking.website-install-url:https://vedicmeet.com/install}") String websiteInstallUrl,
            @Value("${vedicmeet.tracking.app-store-url:}") String appStoreUrl,
            @Value("${vedicmeet.tracking.app-scheme:}") String appScheme,
            @Value("${vedicmeet.tracking.android-package-name:}") String androidPackage,
            @Value("${vedicmeet.tracking.ios-app-id:}") String iosAppId,
            @Value("${vedicmeet.tracking.matching-window-days:7}") int matchingWindowDays,
            @Value("${vedicmeet.tracking.click-expiration-days:30}") int expirationDays) {
        this.mongo = mongo; this.mapper = mapper; this.websiteInstallUrl = websiteInstallUrl;
        this.appStoreUrl = appStoreUrl; this.appScheme = appScheme;
        this.androidPackage = androidPackage; this.iosAppId = iosAppId;
        this.matchingWindowDays = matchingWindowDays; this.expirationDays = expirationDays;
    }

    public String installRedirect(Map<String, Object> input, ClientContext client) {
        Document click = createClick(input, client);
        String target = appendQuery(websiteInstallUrl, trackingQuery(input, click.getObjectId("_id")));
        update(click.getObjectId("_id"), new Document("redirect_url", target));
        return target;
    }

    public Map<String, Object> installUrls(Map<String, Object> input, ClientContext client) {
        Document click = createClick(input, client);
        ObjectId id = click.getObjectId("_id");
        String platform = click.getString("platform");
        String query = trackingQuery(input, id);
        String deepLink = appendQuery(appScheme, query);
        String storeWeb;
        String storeApp = null;
        if ("ios".equals(platform)) {
            String normalized = iosAppId == null ? "" : iosAppId.replaceFirst("^id", "");
            String campaign = text(input, "utm_campaign");
            storeWeb = "https://apps.apple.com/app/id" + normalized
                    + (blank(campaign) ? "" : "?ct=" + encode(campaign));
            storeApp = "itms-apps://apps.apple.com/app/id" + normalized;
        } else {
            storeWeb = appendQuery(appStoreUrl, "referrer=" + encode(query));
            if ("android".equals(platform)) storeApp = "market://details?id=" + androidPackage;
        }
        update(id, new Document("redirect_url", storeWeb));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("click_id", id.toHexString()); out.put("platform", platform);
        out.put("deepLink", deepLink); out.put("storeUrl", storeWeb); out.put("storeAppUrl", storeApp);
        return out;
    }

    public void installVisit(Map<String, Object> input) {
        ObjectId id = optionalId(input.get("click_id"));
        if (id == null) return;
        update(id, new Document("install_page_visited", true)
                .append("install_page_visited_at", new Date())
                .append("install_page_user_agent", input.get("user_agent"))
                .append("install_page_referrer", input.get("referrer")));
    }

    public Map<String, Object> receiveReferrer(Map<String, Object> input, ClientContext client) {
        String platform = text(input, "platform");
        String deviceUuid = text(input, "deviceUUID");
        if (blank(platform) || blank(deviceUuid))
            throw new IllegalArgumentException("Platform and deviceUUID are required");
        Map<String, Object> referrer = extractReferrer(input, platform);
        if (referrer.isEmpty()) throw new IllegalArgumentException("No referrer data found");
        Object clickId = referrer.get("click_id");
        boolean matched = false;
        String userId = text(input, "userId");
        ObjectId id = optionalId(clickId);
        if (id != null && !blank(userId)) matched = matchByReferrer(id, deviceUuid, userId) != null;
        else if (id != null) update(id, new Document("device_uuid", deviceUuid));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("referrer_data", referrer); out.put("matched", matched); out.put("click_id", clickId);
        return out;
    }

    public Document status(String clickId) {
        ObjectId id = requiredId(clickId, "clickId");
        Document event = collection().find(new Document("_id", id)).first();
        if (event == null) throw new IllegalArgumentException("Click event not found");
        return new Document("clickId", event.get("_id")).append("source", event.get("source"))
                .append("utm_source", event.get("utm_source")).append("utm_medium", event.get("utm_medium"))
                .append("utm_campaign", event.get("utm_campaign")).append("is_matched", event.get("is_matched"))
                .append("matched_user_id", event.get("matched_user_id")).append("matched_at", event.get("matched_at"))
                .append("clicked_at", event.get("clicked_at")).append("platform", event.get("platform"));
    }

    public Document matchRegistration(String deviceUuid, String ip, String userAgent, String userId) {
        Date now = new Date();
        Document active = new Document("is_matched", false).append("expires_at", new Document("$gt", now));
        if (!blank(deviceUuid)) {
            Document byDevice = new Document(active).append("device_uuid", deviceUuid);
            Document matched = claimLatest(byDevice, deviceUuid, userId);
            if (matched != null) return matched;
        }
        Date window = Date.from(Instant.now().minus(Duration.ofDays(matchingWindowDays)));
        if (!blank(ip)) {
            Document byIp = new Document(active).append("ip_address", ip)
                    .append("clicked_at", new Document("$gte", window));
            Document matched = claimLatest(byIp, deviceUuid, userId);
            if (matched != null) return matched;
        }
        return null;
    }

    private Document createClick(Map<String, Object> input, ClientContext client) {
        String platform = normalizePlatform(text(input, "platform"), client.userAgent());
        Date now = new Date();
        Document row = new Document("_id", new ObjectId());
        TRACKING_FIELDS.forEach(field -> row.put(field, input.get(field)));
        row.put("ip_address", client.ipAddress()); row.put("user_agent", client.userAgent());
        row.put("device_fingerprint", fingerprint(client)); row.put("platform", platform);
        row.put("original_url", client.originalUrl()); row.put("is_matched", false);
        row.put("matched_user_id", null); row.put("is_app_installed", false);
        row.put("install_page_visited", false); row.put("clicked_at", now);
        row.put("expires_at", Date.from(now.toInstant().plus(Duration.ofDays(expirationDays))));
        row.put("createdAt", now); row.put("updatedAt", now);
        collection().insertOne(row);
        return row;
    }

    private Document matchByReferrer(ObjectId id, String deviceUuid, String userId) {
        return collection().findOneAndUpdate(new Document("_id", id).append("is_matched", false),
                new Document("$set", new Document("is_matched", true).append("matched_user_id", userId)
                        .append("matched_at", new Date()).append("device_uuid", deviceUuid)
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    private Document claimLatest(Document filter, String deviceUuid, String userId) {
        Document set = new Document("is_matched", true).append("matched_user_id", userId)
                .append("matched_at", new Date()).append("updatedAt", new Date());
        if (!blank(deviceUuid)) set.put("device_uuid", deviceUuid);
        return collection().findOneAndUpdate(filter, new Document("$set", set),
                new FindOneAndUpdateOptions().sort(new Document("clicked_at", -1))
                        .returnDocument(ReturnDocument.AFTER));
    }

    private Map<String, Object> extractReferrer(Map<String, Object> input, String platform) {
        Map<String, Object> out = new LinkedHashMap<>();
        String encoded = "android".equalsIgnoreCase(platform)
                ? firstNonBlank(text(input, "referrer"), text(input, "referrer_string"), text(input, "install_referrer"))
                : firstNonBlank(text(input, "campaign"), text(input, "ct"));
        if (!blank(encoded)) out.putAll(parseQuery(encoded));
        if (out.isEmpty()) {
            TRACKING_FIELDS.forEach(field -> putIfPresent(out, field, input.get(field)));
            putIfPresent(out, "click_id", input.get("click_id"));
        }
        if (!out.isEmpty()) {
            out.put("platform", platform.toLowerCase(Locale.ENGLISH));
            out.put("source_type", text(input, "source_type") == null
                    ? (blank(encoded) ? "deep_link" : "android".equalsIgnoreCase(platform)
                    ? "play_store_referrer" : "app_store_campaign") : text(input, "source_type"));
        }
        return out;
    }

    private Map<String, Object> parseQuery(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            for (String pair : decoded.split("&")) {
                int equals = pair.indexOf('=');
                if (equals < 0) continue;
                String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                if (TRACKING_FIELDS.contains(key) || "click_id".equals(key) || "device_uuid".equals(key))
                    out.put(key, value);
            }
        } catch (IllegalArgumentException ignored) { return Map.of(); }
        return out;
    }

    private String trackingQuery(Map<String, Object> input, ObjectId clickId) {
        List<String> pairs = new ArrayList<>();
        TRACKING_FIELDS.forEach(field -> {
            String value = text(input, field); if (!blank(value)) pairs.add(encode(field) + "=" + encode(value));
        });
        pairs.add("click_id=" + encode(clickId.toHexString()));
        return String.join("&", pairs);
    }

    private String appendQuery(String base, String query) {
        String safeBase = base == null ? "" : base;
        if (blank(query)) return safeBase;
        return safeBase + (safeBase.contains("?") ? "&" : "?") + query;
    }

    private String fingerprint(ClientContext c) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("ua", lower(c.userAgent())); root.put("ip", value(c.ipAddress()));
            root.put("lang", firstCsv(c.acceptLanguage())); root.put("enc", firstCsv(c.acceptEncoding()));
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("sec-ch-ua", value(c.clientHints().get("sec-ch-ua")));
            headers.put("sec-ch-ua-mobile", value(c.clientHints().get("sec-ch-ua-mobile")));
            headers.put("sec-ch-ua-platform", value(c.clientHints().get("sec-ch-ua-platform")));
            headers.put("sec-ch-ua-arch", value(c.clientHints().get("sec-ch-ua-arch")));
            headers.put("sec-ch-ua-model", value(c.clientHints().get("sec-ch-ua-model")));
            root.put("headers", headers);
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(mapper.writeValueAsBytes(root)));
        } catch (Exception impossible) { throw new IllegalStateException("FINGERPRINT_FAILED", impossible); }
    }

    private void update(ObjectId id, Document values) {
        values.put("updatedAt", new Date());
        collection().updateOne(new Document("_id", id), new Document("$set", values));
    }
    private com.mongodb.client.MongoCollection<Document> collection() {
        return mongo.getCollection(Collections.CLICK_TRACKINGS);
    }
    private String normalizePlatform(String requested, String userAgent) {
        String platform = lower(requested);
        if (List.of("android", "ios").contains(platform)) return platform;
        String ua = lower(userAgent);
        if (ua.contains("android")) return "android";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) return "ios";
        return "unknown";
    }
    private ObjectId requiredId(Object value, String field) {
        ObjectId id = optionalId(value); if (id == null) throw new IllegalArgumentException(field + " is required");
        return id;
    }
    private ObjectId optionalId(Object value) {
        if (value instanceof ObjectId id) return id;
        return value != null && ObjectId.isValid(String.valueOf(value)) ? new ObjectId(String.valueOf(value)) : null;
    }
    private String text(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key); return value == null ? null : String.valueOf(value);
    }
    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value; return null;
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String lower(String value) { return value(value).toLowerCase(Locale.ENGLISH).trim(); }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private String firstCsv(String value) { return value(value).split(",", 2)[0].trim(); }
    private String encode(String value) { return URLEncoder.encode(value(value), StandardCharsets.UTF_8); }
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    public record ClientContext(String ipAddress, String userAgent, String originalUrl,
                                String acceptLanguage, String acceptEncoding,
                                Map<String, String> clientHints) { }
}
