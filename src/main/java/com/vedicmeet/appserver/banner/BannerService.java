package com.vedicmeet.appserver.banner;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.pricing.OfferService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node BannerService.home -> _fetchBannerHome (utils/classes/banner.js).
 *
 * IMPORTANT — this endpoint is NOT a pure read:
 *   - the consultant branch WRITES boost state (consultant_boosts) via updateOne /
 *     BoostTimeService.updateTimeDuration;
 *   - the user branch computes per-consultant PRICING via OfferService.
 * Both behaviours are reproduced exactly. Because of the write + pricing, the contract
 * harness must verify BOTH the response AND the boost write (see BANNER_PHASE2_SPEC.md)
 * before this is enabled on /v2.
 *
 * Big static aggregations are the exact Mongo JSON from Node, parsed via Document.parse
 * with placeholders substituted (__MEDIA_URL__, __MATCH_LANGUAGES__, __USER_OID__ using
 * MongoDB extended-JSON {"$oid":...}). Dynamic reads/writes are built programmatically.
 */
@Service
public class BannerService {

    private static final long BANNER_LIST_TTL = 60 * 60; // 1h

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;
    private final BoostTimeService boostTimeService;
    private final BannerMetricsService metricsService;
    private final OfferService offerService;
    private final int bannerCount;

    public BannerService(MongoTemplate mongo, CacheService cache, AppConstants constants,
                         BoostTimeService boostTimeService, BannerMetricsService metricsService,
                         OfferService offerService,
                         @Value("${BANNER_COUNT:5}") int bannerCount) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
        this.boostTimeService = boostTimeService;
        this.metricsService = metricsService;
        this.offerService = offerService;
        this.bannerCount = bannerCount;
    }

    /** home() in Node early-returns _fetchBannerHome (the cache wrapper after it is dead code). */
    public Map<String, Object> home(String userType, String language, Document user) {
        Document params = new Document("userType", userType).append("status", true);
        return fetchBannerHome(userType, language, user, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchBannerHome(String userType, String language, Document user, Document params) {
        Map<String, Object> returnObject = new LinkedHashMap<>();
        long noticeCount = 0;

        // Banner list (populate categoryId), cached 1h under banner:list:{userType}:{status}
        String bannerCacheKey = "banner:list:" + userType + ":" + params.get("status");
        List<Document> list = cache.get(bannerCacheKey, List.class);
        if (list == null) {
            list = loadBannerList(params);
            cache.set(bannerCacheKey, list, BANNER_LIST_TTL);
        }

        if ("cons".equals(userType)) {
            ObjectId consId = user.getObjectId("_id");
            String consIdStr = consId.toHexString();

            // notice count
            Date startDate = user.get("createdAt") instanceof Date ? (Date) user.get("createdAt") : null;
            Date endDate = new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000);
            Document createdAtRange = new Document();
            if (startDate != null) createdAtRange.append("$gte", startDate);
            createdAtRange.append("$lte", endDate);
            Document noticeQuery = new Document("createdAt", createdAtRange)
                    .append("isRead", new Document("$nin", Arrays.asList(consId)))
                    .append("$and", Arrays.asList(new Document("$or", Arrays.asList(
                            new Document("consultantId", consId),
                            new Document("userType", "cons")))));
            noticeCount = mongo.getCollection(Collections.NOTICE_BOARDS).countDocuments(noticeQuery);

            Document masterData = mongo.getCollection(Collections.MASTERS).find(new Document())
                    .projection(new Document("_id", 0).append("applicationDetails", 1)).first();

            Date todayDate = utcMidnight();
            Date tomorrow = new Date(todayDate.getTime() + 24L * 60 * 60 * 1000);

            List<Document> consultantAvailable = mongo.getCollection(Collections.CONSULTANTS)
                    .find(new Document("consultantId", consId)
                            .append("date", new Document("$gte", todayDate).append("$lt", tomorrow)))
                    .projection(new Document("status", 0).append("_id", 0).append("__v", 0)
                            .append("updatedAt", 0).append("createdAt", 0))
                    .into(new ArrayList<>());

            long activeFixedSessions = mongo.getCollection(Collections.WAITLISTS).countDocuments(
                    new Document("consultant_id", consIdStr).append("used_for", "session").append("status", "waiting"));

            // boost (WRITE side-effects)
            Document boostForToday = mongo.getCollection(Collections.CONSULTANT_BOOSTS).find(
                    new Document("consultantId", consId)
                            .append("lastBoostDate", new Document("$gte", todayDate).append("$lt", tomorrow))).first();
            if (boostForToday == null) {
                mongo.getCollection(Collections.CONSULTANT_BOOSTS).updateOne(
                        new Document("consultantId", consId),
                        new Document("$set", new Document("callActive", false)
                                .append("chatActive", false).append("videoActive", false)));
            } else {
                boostForToday = boostTimeService.updateTimeDuration(boostForToday, null);
            }

            Document consDetails = mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", consId))
                    .projection(new Document("emergencyCall", 1).append("freeTrailOffer", 1).append("price", 1)
                            .append("lastPriceChangeDate", 1).append("isChatLive", 1)
                            .append("isCallLive", 1).append("isVideoLive", 1)).first();

            Map<String, Object> metrics = metricsService.loadConsultantBannerMetrics(consIdStr, consId);
            returnObject.put("bannerData", metricsService.buildConsultantBannerData(metrics));

            returnObject.put("activeFixedSessions", activeFixedSessions);
            returnObject.put("list", list);
            returnObject.put("noticeCount", noticeCount);
            returnObject.put("applicationDetails",
                    masterData != null && masterData.get("applicationDetails") != null
                            ? masterData.get("applicationDetails") : new Document());
            returnObject.put("consultantAvailable", consultantAvailable);
            returnObject.put("boostData", boostForToday);
            returnObject.put("consDetails", consDetails);
            returnObject.put("waringCount", mongo.getCollection(Collections.WARNINGS).countDocuments(
                    new Document("consultantId", consId).append("warningStatus", false)));

        } else if ("user".equals(userType)) {
            ObjectId userId = user.getObjectId("_id");
            String userOid = userId.toHexString();

            Document userMasterSettings = mongo.getCollection(Collections.SEED_MASTERS)
                    .find(new Document("for", "userMasterSettings"))
                    .projection(new Document("data", 1).append("_id", 0)).first();
            Document settingsData = userMasterSettings == null ? null : (Document) userMasterSettings.get("data");
            List<Object> dynamicBannerList = settingsData == null ? null : (List<Object>) settingsData.get("bannerList");
            Object introVideoUrl = settingsData == null ? null : settingsData.get("introVideoUrl");
            Object videoReviewsFromMaster = settingsData == null ? null : settingsData.get("videoReviews");

            if (dynamicBannerList != null && !dynamicBannerList.isEmpty()) {
                list = (List<Document>) (List<?>) dynamicBannerList;
            } else {
                List<Object> problems = (List<Object>) user.get("problems");
                if (problems != null) {
                    java.util.Set<String> problemHex = new java.util.HashSet<>();
                    for (Object p : problems) problemHex.add(new ObjectId(p.toString()).toHexString());
                    List<Document> filtered = new ArrayList<>();
                    for (Document item : list) {
                        Object cat = item.get("categoryId");
                        if (cat instanceof Document c && c.get("_id") != null
                                && problemHex.contains(c.get("_id").toString())) {
                            filtered.add(item);
                        }
                    }
                    java.util.Collections.shuffle(filtered); // Node shuffleArray (non-deterministic)
                    list = filtered.subList(0, Math.min(bannerCount, filtered.size()));
                }
            }

            Document masterData = mongo.getCollection(Collections.MASTERS).find(new Document())
                    .projection(new Document("_id", 0).append("masterCoupon", 1)).first();

            String matchLanguagesJson = buildMatchLanguagesJson(language);

            List<Document> populatConsultant = mongo.getCollection(Collections.CONSULTANTS)
                    .aggregate(parsePipeline(POPULAR_CONSULTANT.replace("__MATCH_LANGUAGES__", matchLanguagesJson)))
                    .into(new ArrayList<>());

            List<Document> explore = mongo.getCollection(Collections.EXPLORES)
                    .aggregate(parsePipeline(EXPLORE_ONE.replace("__USER_OID__", userOid)))
                    .into(new ArrayList<>());

            List<Document> promocodeList = mongo.getCollection(Collections.COUPONS)
                    .aggregate(parsePipeline(PROMOCODE)).into(new ArrayList<>());

            List<Document> textFeedbackList = mongo.getCollection(Collections.FEEDBACKS)
                    .aggregate(parsePipeline(TEXT_FEEDBACK)).into(new ArrayList<>());

            Map<String, Object> newUserInPlatform = new LinkedHashMap<>();
            newUserInPlatform.put("isNew", false);
            Document device = (Document) user.get("device");
            List<Object> uuids = device == null ? null : (List<Object>) device.get("uuid");
            if (uuids != null && !uuids.isEmpty()) {
                Object lastToken = uuids.get(uuids.size() - 1);
                Document existing = mongo.getCollection(Collections.WAITLISTS)
                        .find(new Document("deviceUsedToken", lastToken).append("status", "complete")).first();
                if (existing == null) {
                    Document masterCoupon = masterData == null ? null : (Document) masterData.get("masterCoupon");
                    newUserInPlatform.put("isNew", true);
                    newUserInPlatform.put("couponCode", masterCoupon != null && masterCoupon.get("couponCode") != null
                            ? masterCoupon.get("couponCode") : "");
                    newUserInPlatform.put("totalMinutes", masterCoupon != null && masterCoupon.get("totalMinutes") != null
                            ? masterCoupon.get("totalMinutes") : 0);
                }
            }

            // per-consultant offer discount (PRICING)
            for (Document element : populatConsultant) {
                Number price = element.get("price", Number.class);
                Map<String, Object> offerResponse = offerService.getConsOfferDiscountForUser(
                        userId, element.getObjectId("_id"), price);
                Boolean ok = (Boolean) offerResponse.get("status");
                element.put("discountPrice", Boolean.TRUE.equals(ok) ? offerResponse.get("discountPrice") : price);
            }

            Map<String, Object> userMembership = new LinkedHashMap<>();
            userMembership.put("isMembership", false);
            if (Boolean.TRUE.equals(user.getBoolean("isMembership", false))) {
                userMembership.put("isMembership", true);
                userMembership.put("planType", user.get("planType"));
                userMembership.put("discountPercentage", user.get("discountPercentage"));
                userMembership.put("consultantId", user.get("consultantId"));
            }

            returnObject.put("list", list);
            returnObject.put("populatConsultant", populatConsultant);
            returnObject.put("community", new ArrayList<>());
            returnObject.put("explore", explore != null ? explore : new ArrayList<>());
            returnObject.put("promocode", promocodeList);
            returnObject.put("feedbackList", videoReviewsFromMaster);
            returnObject.put("textFeedbackList", textFeedbackList);
            returnObject.put("totalCompleteOrder", 0);
            if (introVideoUrl != null) {
                returnObject.put("introVideoUrl", introVideoUrl);
            }
            returnObject.put("userMembership", userMembership);
            returnObject.put("newUserInPlatform", newUserInPlatform);
        }

        return returnObject;
    }

    // ---- helpers ----

    private List<Document> loadBannerList(Document params) {
        // banner.find(params).populate({categoryId: {_id,title,status}}).sort({_id:-1})
        List<Document> pipeline = Arrays.asList(
                new Document("$match", params),
                new Document("$sort", new Document("_id", -1)),
                new Document("$lookup", new Document("from", Collections.CATEGORIES)
                        .append("localField", "categoryId").append("foreignField", "_id").append("as", "_cat")),
                new Document("$addFields", new Document("categoryId", new Document("$cond", Arrays.asList(
                        new Document("$gt", Arrays.asList(new Document("$size", "$_cat"), 0)),
                        new Document("$let", new Document("vars", new Document("c",
                                new Document("$arrayElemAt", Arrays.asList("$_cat", 0))))
                                .append("in", new Document("_id", "$$c._id").append("title", "$$c.title").append("status", "$$c.status"))),
                        null)))),
                new Document("$project", new Document("_cat", 0)));
        return mongo.getCollection(Collections.BANNERS).aggregate(pipeline).into(new ArrayList<>());
    }

    private Date utcMidnight() {
        java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.set(java.util.Calendar.HOUR_OF_DAY, 0);
        utc.set(java.util.Calendar.MINUTE, 0);
        utc.set(java.util.Calendar.SECOND, 0);
        utc.set(java.util.Calendar.MILLISECOND, 0);
        return utc.getTime();
    }

    /** Reproduces Node's matchLanguages array from the request language. */
    private String buildMatchLanguagesJson(String languageInput) {
        String language = (languageInput == null || languageInput.isEmpty()) ? "en" : languageInput;
        String lower = language.toLowerCase();
        List<String> m = new ArrayList<>();
        m.add(lower);
        switch (lower) {
            case "en": case "english": addAll(m, "english", "en", "English"); break;
            case "hi": case "hindi": addAll(m, "hindi", "hi", "Hindi"); break;
            case "kn": case "kannada": addAll(m, "kannada", "kn", "Kannada"); break;
            case "te": case "telugu": addAll(m, "telugu", "te", "Telugu"); break;
            case "ta": case "tamil": addAll(m, "tamil", "ta", "Tamil"); break;
            default: break;
        }
        String capitalized = language.substring(0, 1).toUpperCase() + language.substring(1).toLowerCase();
        if (!m.contains(capitalized)) m.add(capitalized);
        if (!m.contains(language.toLowerCase())) m.add(language.toLowerCase());
        if (!m.contains(language.toUpperCase())) m.add(language.toUpperCase());

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < m.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(m.get(i).replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    private void addAll(List<String> m, String... vals) {
        for (String v : vals) m.add(v);
    }

    private List<Document> parsePipeline(String json) {
        Document wrap = Document.parse("{\"p\":" + json.replace("__MEDIA_URL__", constants.mediaUrl) + "}");
        return wrap.getList("p", Document.class);
    }

    // ===== static aggregation pipelines (verbatim Mongo JSON from Node) =====

    private static final String POPULAR_CONSULTANT = """
        [
          { "$match": { "status": true, "isDeleted": false, "isAdminVerify": true } },
          { "$lookup": { "from": "waitlists", "let": { "consultantId": { "$toString": "$_id" } },
            "pipeline": [
              { "$match": { "$expr": { "$eq": ["$consultant_id", "$$consultantId"] }, "status": "progress" } },
              { "$limit": 1 }, { "$project": { "_id": 1 } }
            ], "as": "_activeSessions" } },
          { "$addFields": {
            "sortPriority": { "$cond": { "if": { "$or": [
                { "$eq": ["$sessionsStatus.isChatLive", true] },
                { "$eq": ["$sessionsStatus.isVoiceLive", true] } ] },
              "then": { "$cond": { "if": { "$gt": [ { "$size": "$_activeSessions" }, 0 ] }, "then": 1, "else": 0 } },
              "else": 2 } },
            "currentSession": { "$size": "$_activeSessions" },
            "languagePriority": { "$cond": { "if": { "$cond": {
                "if": { "$isArray": "$language" },
                "then": { "$gt": [ { "$size": { "$setIntersection": ["$language", __MATCH_LANGUAGES__] } }, 0 ] },
                "else": { "$in": ["$language", __MATCH_LANGUAGES__] } } },
              "then": 0, "else": 1 } },
            "sortRanking": { "$ifNull": ["$Ranking", 999999] },
            "tag": "$details.tag"
          } },
          { "$sort": { "sortPriority": 1, "languagePriority": 1, "sortRanking": 1 } },
          { "$limit": 5 },
          { "$lookup": { "from": "review_and_ratings", "let": { "consultantId": "$_id" },
            "pipeline": [
              { "$match": { "$expr": { "$eq": ["$$consultantId", "$consultantId"] } } },
              { "$group": { "_id": null, "average": { "$avg": "$rating" } } },
              { "$project": { "_id": 0, "average": { "$round": ["$average", 1] } } }
            ], "as": "consRating" } },
          { "$lookup": { "from": "coupons", "let": { "consultantId": "$_id" },
            "pipeline": [
              { "$match": { "$expr": { "$and": [
                { "$in": ["$$consultantId", "$consultantId"] },
                { "$eq": ["$status", true] },
                { "$eq": ["$couponType", "OFFERS"] } ] } } },
              { "$project": { "userType": 1, "couponDiscount": 1 } }
            ], "as": "offers" } },
          { "$project": {
            "name": { "$cond": [ { "$eq": ["$accountName", ""] }, "$name", "$accountName" ] },
            "email": "$details.email",
            "price": "$price.default",
            "profileImage": { "$cond": [ { "$eq": ["$profileImage", ""] }, "",
              { "$cond": [ { "$regexMatch": { "input": "$profileImage", "regex": "^https?://" } },
                "$profileImage",
                { "$concat": ["https://vedic-meet-bucket.s3.ap-south-1.amazonaws.com/", "$profileImage"] } ] } ] },
            "createdAt": 1,
            "isChatLive": "$sessionsStatus.isChatLive",
            "isCallLive": "$sessionsStatus.isVoiceLive",
            "score": 1,
            "boostOnProfile": "$boostSessionsStatus.isChatLive",
            "rating": { "$cond": { "if": { "$eq": [ { "$size": "$consRating" }, 0 ] }, "then": 0,
              "else": { "$avg": "$consRating.average" } } },
            "offer": { "$cond": { "if": { "$gt": [ { "$size": "$offers" }, 0 ] },
              "then": { "$map": { "input": "$offers", "as": "offer",
                "in": { "userType": "$$offer.userType", "couponDiscount": "$$offer.couponDiscount" } } },
              "else": null } }
          } }
        ]
        """;

    private static final String EXPLORE_ONE = """
[
{ "$match": { "status": true, "consultantId": { "$exists": true }, "notIntrest": { "$nin": [ { "$oid": "__USER_OID__" } ] }, "notRecommed": { "$nin": [ { "$oid": "__USER_OID__" } ] } } },
{ "$lookup": { "from": "consultants", "localField": "consultantId",
          "foreignField": "_id", "as": "_consultantJoin" } },
{ "$addFields": { "consultant": { "$cond": [
          { "$gt": [ { "$size": { "$ifNull": ["$_consultantJoin", []] } }, 0 ] },
          { "$let": { "vars": { "c": { "$arrayElemAt": [ { "$ifNull": ["$_consultantJoin", []] }, 0 ] } },
            "in": {
              "_id": "$$c._id",
              "name": { "$ifNull": ["$$c.name", "$$c.accountName"] },
              "accountName": "$$c.accountName",
              "profileImage": { "$cond": [
                { "$eq": [ { "$ifNull": ["$$c.profileImage", ""] }, "" ] }, "",
                { "$cond": [
                  { "$or": [
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$c.profileImage" }, 0, 7 ] }, "http://" ] },
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$c.profileImage" }, 0, 8 ] }, "https://" ] }
                  ] },
                  { "$toString": "$$c.profileImage" },
                  { "$concat": ["__MEDIA_URL__", { "$toString": "$$c.profileImage" }] }
                ] }
              ] },
              "greenTick": { "$ifNull": ["$$c.greenTick", false] },
              "expertiseLine": { "$let": { "vars": { "ex": { "$ifNull": ["$$c.expertise", []] } },
                "in": { "$cond": [
                  { "$and": [ { "$isArray": "$$ex" }, { "$gt": [ { "$size": "$$ex" }, 0 ] } ] },
                  { "$arrayElemAt": ["$$ex", 0] },
                  { "$ifNull": ["$$c.PersonalDetails.tag", "Astrologer"] }
                ] } } }
            } } },
          null
        ] } } },
{ "$project": { "_consultantJoin": 0 } },
{ "$lookup": { "from": "comments", "let": { "exploreId": "$_id" },
          "pipeline": [
            { "$match": { "$expr": { "$eq": ["$$exploreId", "$exploreId"] } } },
            { "$lookup": { "from": "users", "localField": "userId", "foreignField": "_id", "as": "user" } },
            { "$unwind": "$user" },
            { "$project": { "_id": 1, "comment": 1, "createdAt": 1,
              "user": { "_id": 1, "name": 1, "profileImage": { "$cond": [
                { "$eq": ["$user.profileImage", ""] }, "",
                { "$concat": ["__MEDIA_URL__", "$user.profileImage"] }
              ] } } } },
            { "$sort": { "createdAt": -1 } }
          ], "as": "commentsList" } },
{ "$addFields": { "isLiked": { "$cond": { "if": { "$in": [ { "$oid": "__USER_OID__" }, "$likes.userId"] }, "then": true, "else": false } } } },
{ "$addFields": { "isBookmark": { "$cond": { "if": { "$in": [ { "$oid": "__USER_OID__" }, "$bookmark"] }, "then": true, "else": false } } } },
{ "$addFields": { "media": { "$let": {
          "vars": { "mediaItems": { "$cond": [
            { "$isArray": "$media" }, "$media",
            { "$cond": [ { "$ne": ["$media", null] }, ["$media"], [] ] }
          ] } },
          "in": { "$map": { "input": "$$mediaItems", "as": "m", "in": { "$mergeObjects": [ "$$m", {
            "url": { "$cond": [
              { "$eq": [ { "$toString": { "$ifNull": ["$$m.url", ""] } }, "" ] }, "",
              { "$cond": [
                { "$or": [
                  { "$eq": [ { "$substrCP": [ { "$toString": "$$m.url" }, 0, 7 ] }, "http://" ] },
                  { "$eq": [ { "$substrCP": [ { "$toString": "$$m.url" }, 0, 8 ] }, "https://" ] }
                ] },
                { "$toString": "$$m.url" },
                { "$concat": ["__MEDIA_URL__", { "$toString": "$$m.url" }] }
              ] }
            ] },
            "music": { "$let": { "vars": { "mu": { "$ifNull": ["$$m.music", ""] } },
              "in": { "$cond": [
                { "$eq": [ { "$toString": "$$mu" }, "" ] }, "",
                { "$cond": [
                  { "$or": [
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$mu" }, 0, 7 ] }, "http://" ] },
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$mu" }, 0, 8 ] }, "https://" ] }
                  ] },
                  { "$toString": "$$mu" },
                  { "$concat": ["__MEDIA_URL__", { "$toString": "$$mu" }] }
                ] }
              ] } } },
            "playlistUrl": { "$let": { "vars": { "pu": { "$ifNull": ["$$m.playlistUrl", ""] } },
              "in": { "$cond": [
                { "$eq": [ { "$toString": "$$pu" }, "" ] }, "",
                { "$cond": [
                  { "$or": [
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$pu" }, 0, 7 ] }, "http://" ] },
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$pu" }, 0, 8 ] }, "https://" ] }
                  ] },
                  { "$toString": "$$pu" },
                  { "$concat": ["__MEDIA_URL__", { "$toString": "$$pu" }] }
                ] }
              ] } } }
          } ] } } }
        } } } },
{ "$project": {
          "title": 1, "hastag": 1, "description": 1, "consultantId": 1, "consultant": 1,
          "exploreImage": { "$cond": [ { "$eq": ["$exploreImage", ""] }, "", { "$concat": ["__MEDIA_URL__", "$exploreImage"] } ] },
          "media": 1, "createdAt": 1, "isLiked": 1, "isBookmark": 1,
          "videoThumbnail": { "$cond": [ { "$eq": ["$videoThumbnail", ""] }, "", { "$concat": ["__MEDIA_URL__", "$videoThumbnail"] } ] },
          "totalLikes": { "$size": "$likes" },
          "totalComments": { "$size": "$commentsList" },
          "commentsList": 1
        } },
{ "$sort": { "_id": -1 } },
{ "$limit": 1 }
]
        """;

    private static final String PROMOCODE = """
        [
          { "$match": { "status": true, "couponType": "PROMOCODE" } },
          { "$project": {
            "couponType": 1, "title": 1,
            "couponImage": { "$cond": [ { "$eq": ["$image", ""] }, "", { "$concat": ["__MEDIA_URL__", "$image"] } ] },
            "couponDiscount": 1, "couponStartDate": 1, "couponEndDate": 1
          } },
          { "$sort": { "createdAt": -1 } },
          { "$limit": 3 }
        ]
        """;

    private static final String TEXT_FEEDBACK = """
        [
          { "$match": { "rating": { "$in": [4, 5] }, "status": true, "userType": "user" } },
          { "$lookup": { "from": "users", "let": { "userId": "$userId" },
            "pipeline": [
              { "$match": { "$expr": { "$eq": ["$$userId", "$_id"] } } },
              { "$project": { "name": 1, "email": 1, "profileImage": 1, "address": 1, "city": 1, "country": 1 } }
            ], "as": "userDetails" } },
          { "$unwind": "$userDetails" },
          { "$sort": { "createdAt": -1 } },
          { "$limit": 6 }
        ]
        """;
}
