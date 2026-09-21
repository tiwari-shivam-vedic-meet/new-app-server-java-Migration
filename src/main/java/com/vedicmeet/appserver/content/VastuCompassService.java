package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.discovery.MembershipService;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.pricing.OfferService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mobile parity for Node {@code utils/classes/vastu-compass.js} methods. */
@Service
public class VastuCompassService {
    private static final long CACHE_SECONDS = 2 * 60 * 60;
    private static final List<String> ASTROLOGY_SKILLS = List.of(
            "Astrology", "Vastu", "Numerology", "Palmistry", "Horoscope", "Kundali", "Match Making");

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;
    private final MembershipService memberships;
    private final OfferService offers;
    private final PushNotificationService push;
    private final CleverTapClient cleverTap;
    private final String videoUrl;
    private final String infoVideoUrl;
    private final String videoImageUrl;
    private final String infoImageUrl;

    public VastuCompassService(MongoTemplate mongo, CacheService cache, AppConstants constants,
                               MembershipService memberships, OfferService offers,
                               PushNotificationService push, CleverTapClient cleverTap,
                               @Value("${vedicmeet.vastu.video-url:}") String videoUrl,
                               @Value("${vedicmeet.vastu.info-video-url:}") String infoVideoUrl,
                               @Value("${vedicmeet.vastu.video-image-url:}") String videoImageUrl,
                               @Value("${vedicmeet.vastu.info-image-url:}") String infoImageUrl) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
        this.memberships = memberships;
        this.offers = offers;
        this.push = push;
        this.cleverTap = cleverTap;
        this.videoUrl = videoUrl;
        this.infoVideoUrl = infoVideoUrl;
        this.videoImageUrl = videoImageUrl;
        this.infoImageUrl = infoImageUrl;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> categories() {
        String key = "vastu:category:list";
        Map<String, Object> hit = cache.get(key, Map.class);
        if (hit != null) return hit;

        List<Document> list = mongo.getCollection(Collections.VASTU_COMPASS_CATEGORIES).aggregate(List.of(
                new Document("$match", new Document("status", true)),
                new Document("$group", new Document("_id", "$category")
                        .append("position", new Document("$first", "$position"))
                        .append("titles", new Document("$push", new Document("title", "$title")
                                .append("titleId", "$_id").append("image", media("$image"))))),
                new Document("$project", new Document("_id", 0).append("category", "$_id")
                        .append("titles", 1).append("position", 1)),
                new Document("$sort", new Document("position", 1))))
                .into(new ArrayList<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("vastuYoutubeVideoLink", value(videoUrl));
        result.put("vastuYoutubeVideoLinkInfo", value(infoVideoUrl));
        result.put("vastuYoutubeVideoLinkImage", value(videoImageUrl));
        result.put("vastuYoutubeVideoLinkInfoImage", value(infoImageUrl));
        cache.set(key, result, CACHE_SECONDS);
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> report(Map<String, Object> input, Document user) {
        String type = text(input, "type");
        String direction = required(input, "direction");
        String title = required(input, "vastuTitle");
        Document master = mongo.getCollection(Collections.MASTERS)
                .find().projection(new Document("_id", 0).append("vastuLogic", 1)).first();
        Object logicObject = master == null ? null : master.get("vastuLogic");
        Map<String, Object> logic = asMap(logicObject);
        if (logic == null) throw new IllegalStateException("VASTULOGIC_MISSING");
        Map<String, Object> directionLogic = asMap(logic.get(direction));
        if (directionLogic == null) throw new IllegalArgumentException("DIRECTION_IS_NOT_MATCHED");
        if (!directionLogic.containsKey(title)) throw new IllegalArgumentException("TITLE_IS_NOT_MATCHED");
        Object zone = directionLogic.get(title);

        List<Object> colors = new ArrayList<>();
        for (Object entry : logic.values()) {
            Map<String, Object> values = asMap(entry);
            if (values != null) colors.add(values.get(title));
        }

        Document report = null;
        Document record = null;
        List<Document> consultants = new ArrayList<>();
        if ("capture".equals(type)) {
            sendReportReady(user, text(input, "titleId"));
        } else if ("report".equals(type)) {
            ObjectId titleId = objectId(required(input, "titleId"), "titleId");
            List<Document> reports = mongo.getCollection(Collections.VASTU_COMPASS_ZONES).aggregate(List.of(
                    new Document("$match", new Document("zone", zone).append("vastuCategoryId", titleId)),
                    new Document("$lookup", new Document("from", Collections.VASTU_COMPASS_CATEGORIES)
                            .append("let", new Document("vastuCategoryId", "$vastuCategoryId"))
                            .append("pipeline", List.of(
                                    new Document("$match", new Document("$expr", new Document("$eq",
                                            List.of("$$vastuCategoryId", "$_id")))),
                                    new Document("$project", new Document("_id", 0).append("category", 1)
                                            .append("title", 1)
                                            .append("vastuCompassCategoryImage", media("$image")))))
                            .append("as", "vastuCategory")),
                    new Document("$unwind", new Document("path", "$vastuCategory")
                            .append("preserveNullAndEmptyArrays", true)),
                    new Document("$project", new Document("vastuCategoryId", 1).append("zone", 1)
                            .append("descriptionEngish", 1).append("descriptionHindi", 1)
                            .append("status", 1).append("vastuCompassZoneFile", media("$file"))
                            .append("vastuCategory", 1))))
                    .into(new ArrayList<>());
            report = reports.isEmpty() ? null : reports.get(0);

            ObjectId userId = actorId(user);
            Date now = new Date();
            Document update = new Document("titleId", titleId).append("direction", direction)
                    .append("vastuTitle", title).append("zone", zone).append("updatedAt", now);
            record = mongo.getCollection(Collections.USER_VASTU_COMPASS_RECORDS).findOneAndUpdate(
                    new Document("userId", userId).append("titleId", titleId)
                            .append("zone", zone).append("direction", direction),
                    new Document("$set", update).append("$setOnInsert",
                            new Document("userId", userId).append("status", false).append("createdAt", now)),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            consultants = recommendedConsultants(userId);
        }

        Document memberUser = memberships.applyMembership(new Document(user));
        Map<String, Object> membership = new LinkedHashMap<>();
        membership.put("isMembership", Boolean.TRUE.equals(memberUser.getBoolean("isMembership")));
        if (Boolean.TRUE.equals(memberUser.getBoolean("isMembership"))) {
            membership.put("planType", memberUser.get("planType"));
            membership.put("discountPercentage", memberUser.get("discountPercentage"));
            membership.put("consultantId", memberUser.get("consultantId"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zone", zone);
        out.put("report", report);
        out.put("compassColor", colors);
        out.put("recommendConsultant", consultants);
        out.put("userMembership", membership);
        out.put("vastuRecord", record);
        return out;
    }

    public Map<String, Object> reportList(Document user, Integer pageParam, Integer limitParam) {
        int page = pageParam == null ? 1 : Math.max(1, pageParam);
        int limit = limitParam == null ? 100 : Math.max(1, limitParam);
        Document filter = new Document("userId", actorId(user)).append("status", true);
        List<Document> list = mongo.getCollection(Collections.USER_VASTU_COMPASS_RECORDS).aggregate(List.of(
                new Document("$match", filter),
                new Document("$lookup", new Document("from", Collections.VASTU_COMPASS_CATEGORIES)
                        .append("let", new Document("vastuCompassTitle", "$titleId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$eq",
                                        List.of("$_id", "$$vastuCompassTitle")))),
                                new Document("$project", new Document("category", 1).append("title", 1)
                                        .append("vastuCompassCategoryImage", media("$image")))))
                        .append("as", "vastuTitleDetails")),
                new Document("$unwind", new Document("path", "$vastuTitleDetails")
                        .append("preserveNullAndEmptyArrays", true)),
                new Document("$project", new Document("zone", 1)
                        .append("vastuTitle", "$vastuTitleDetails.title")
                        .append("vastuCategory", "$vastuTitleDetails.category")
                        .append("vastuCompassCategoryImage", "$vastuTitleDetails.vastuCompassCategoryImage")
                        .append("direction", 1).append("titleId", 1).append("createdAt", 1)),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", (page - 1) * limit), new Document("$limit", limit)))
                .into(new ArrayList<>());
        return Map.of("list", list, "total",
                mongo.getCollection(Collections.USER_VASTU_COMPASS_RECORDS).countDocuments(filter));
    }

    public void bookmark(String recordId, Boolean status) {
        ObjectId id = objectId(recordId, "vastuRecordId");
        if (status == null) throw new IllegalArgumentException("status is required");
        Document record = mongo.getCollection(Collections.USER_VASTU_COMPASS_RECORDS)
                .find(new Document("_id", id)).first();
        if (record == null) throw new IllegalArgumentException("VASTU_RECORD_NOT_EXIST");
        mongo.getCollection(Collections.USER_VASTU_COMPASS_RECORDS).updateOne(
                new Document("_id", id), new Document("$set",
                        new Document("status", status).append("updatedAt", new Date())));
    }

    private List<Document> recommendedConsultants(ObjectId userId) {
        List<Document> list = mongo.getCollection(Collections.CONSULTANTS).aggregate(List.of(
                new Document("$match", new Document("status", true).append("isDeleted", false)
                        .append("primarySkills", new Document("$in", ASTROLOGY_SKILLS))),
                new Document("$lookup", new Document("from", Collections.REVIEW_AND_RATINGS)
                        .append("let", new Document("consultantId", "$_id"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr", new Document("$eq",
                                        List.of("$$consultantId", "$consultantId")))),
                                new Document("$group", new Document("_id", null)
                                        .append("average", new Document("$avg", "$rating")))))
                        .append("as", "consRating")),
                new Document("$project", new Document("name", new Document("$cond",
                        List.of(new Document("$eq", List.of("$accountName", "")), "$name", "$accountName")))
                        .append("email", 1).append("price", 1).append("isChatLive", 1)
                        .append("isCallLive", 1).append("isVideoLive", 1).append("score", 1)
                        .append("rating", new Document("$cond", new Document("if",
                                new Document("$eq", List.of(new Document("$size", "$consRating"), 0)))
                                .append("then", 0).append("else", new Document("$avg", "$consRating.average"))))
                        .append("createdAt", 1).append("profileImage", media("$profileImage"))),
                new Document("$sort", new Document("createdAt", -1).append("score", -1)),
                new Document("$limit", 6))).into(new ArrayList<>());
        for (Document consultant : list) {
            Document price = consultant.get("price", Document.class);
            Number charge = price == null ? null : price.get("default", Number.class);
            Map<String, Object> offer = offers.getConsOfferDiscountForUser(
                    userId, consultant.getObjectId("_id"), charge);
            consultant.put("discountPrice", Boolean.TRUE.equals(offer.get("status"))
                    ? offer.get("discountPrice") : price);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private void sendReportReady(Document user, String reportId) {
        String body = "Your home Vastu report is ready! Check it out.";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("screen", "MyReportsHistory");
        data.put("reportId", reportId);
        Object device = user.get("device");
        Object tokens = device instanceof Document d ? d.get("fcmToken")
                : device instanceof Map<?, ?> m ? m.get("fcmToken") : null;
        if (tokens instanceof Iterable<?> iterable) {
            for (Object token : iterable) if (token != null)
                push.sendNotificationAndCons("user", String.valueOf(token), body, data,
                        "Vedic Meet", "vastu");
        }
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATIONS).insertOne(new Document("receiverId", actorId(user))
                .append("message", body).append("title", "Vedic Meet")
                .append("userType", "user").append("senderType", "system")
                .append("type", "other").append("data", data)
                .append("readByReceiver", new ArrayList<>()).append("createdAt", now).append("updatedAt", now));
        Object details = user.get("details");
        String phone = details instanceof Document d ? d.getString("phone") : null;
        if (phone != null && cleverTap.isReady()) cleverTap.uploadEvent(phone, "VASTU_COMPASS_ACCESS",
                Map.of("action", "vastu_compass_accessed", "timestamp", Instant.now().toString(),
                        "source", "server"));
    }

    private Document media(String field) {
        return new Document("$cond", new Document("if", new Document("$eq", List.of(field, "")))
                .append("then", "").append("else", new Document("$concat", List.of(constants.mediaUrl, field))));
    }

    private ObjectId actorId(Document user) {
        Object id = user.get("_id");
        if (id instanceof ObjectId objectId) return objectId;
        if (id != null && ObjectId.isValid(String.valueOf(id))) return new ObjectId(String.valueOf(id));
        throw new IllegalArgumentException("ACCOUNT_NOT_FOUND");
    }

    private ObjectId objectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) throw new IllegalArgumentException(field + " is required");
        return new ObjectId(value);
    }

    private String required(Map<String, Object> input, String key) {
        String value = text(input, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String value(String value) { return value == null ? "" : value; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return null;
    }
}
