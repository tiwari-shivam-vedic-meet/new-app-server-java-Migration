package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node UserService.consultantList (utils/classes/user.js L4270-4502).
 * Serves BOTH POST /v2/user/consultant_list and POST /v2/user/top_consultant_list — Node
 * routes both call the same service (only the success message differs).
 *
 * The dynamic $match (search/filters/online-boost) is built programmatically; the
 * waitlist-busy $lookup + sort-priority $addFields + $facet(list,count) tail is the exact
 * Node Mongo JSON via Document.parse, with placeholders __MATCH_LANGUAGES__ / __SKIP__ /
 * __LIMIT__. Busy/queue status per consultant is attached via WaitlistStatusService
 * (Redis billing-clock read). Extra reviews come from seed_masters consultantMasterSettings.
 *
 * Diff-pending: enable on /v2 only after the contract-harness diff is clean on the TEST server.
 */
@Service
public class ConsultantListService {

    private final MongoTemplate mongo;
    private final WaitlistStatusService waitlistStatusService;

    public ConsultantListService(MongoTemplate mongo, WaitlistStatusService waitlistStatusService) {
        this.mongo = mongo;
        this.waitlistStatusService = waitlistStatusService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> consultantList(Map<String, Object> input, Document user) {
        int page = intOf(input.get("page"), 1);
        int limit = intOf(input.get("limit"), 10);
        String search = input.get("search") == null ? "" : input.get("search").toString();
        Map<String, Object> filter = input.get("filter") instanceof Map
                ? (Map<String, Object>) input.get("filter") : new LinkedHashMap<>();
        String boostType = input.get("boostType") == null ? "" : input.get("boostType").toString();
        String language = input.get("language") == null ? "en" : input.get("language").toString();
        int skip = (page - 1) * limit;

        List<String> matchLanguages = computeMatchLanguages(language);

        // ---- base match ----
        Document match = new Document("status", true).append("isDeleted", false).append("isAdminVerify", true);
        if (search != null && !search.trim().isEmpty()) {
            match.append("$or", Arrays.asList(
                    new Document("accountName", new Document("$regex", search).append("$options", "i"))));
        }
        if (filter.get("gender") != null) match.append("details.gender", new Document("$in", filter.get("gender")));
        if (filter.get("language") != null) match.append("language", new Document("$in", filter.get("language")));
        if (filter.get("skills") != null) match.append("expertise", new Document("$in", filter.get("skills")));
        Object expertise = filter.get("expertise");
        if (expertise instanceof List && !((List<?>) expertise).contains("All")) {
            match.append("expertise", new Document("$in", expertise));
        }
        Object isOnline = filter.get("isOnline");
        if (Boolean.TRUE.equals(isOnline) || "true".equals(String.valueOf(isOnline))) {
            if (boostType == null || boostType.isEmpty()) throw new RuntimeException("BOOST_TYPE_REQUIRE");
            if ("CHAT".equals(boostType)) match.append("sessionsStatus.isChatLive", true);
            if ("CALL".equals(boostType)) match.append("sessionsStatus.isVoiceLive", true);
            if ("VIDEO".equals(boostType)) match.append("sessionsStatus.isVideoLive", true);
        }

        // ---- pipeline: $match (dynamic) + verbatim tail ----
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", match));
        String tail = PIPELINE_TAIL
                .replace("__MATCH_LANGUAGES__", jsonStringArray(matchLanguages))
                .replace("__SKIP__", String.valueOf(skip))
                .replace("__LIMIT__", String.valueOf(limit));
        pipeline.addAll(parsePlain(tail));

        Document resultData = mongo.getCollection(Collections.CONSULTANTS).aggregate(pipeline).first();
        List<Document> list = resultData == null ? new ArrayList<>()
                : resultData.getList("list", Document.class, new ArrayList<>());
        List<Document> countArr = resultData == null ? new ArrayList<>()
                : resultData.getList("count", Document.class, new ArrayList<>());
        long count = countArr.isEmpty() ? 0 : ((Number) countArr.get(0).getOrDefault("total", 0)).longValue();

        // ---- extra reviews from consultantMasterSettings ----
        Document masterSettings = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "consultantMasterSettings")).first();
        Document addReviews = masterSettings != null && masterSettings.get("data") instanceof Document
                ? ((Document) masterSettings.get("data")).get("AddReviews", Document.class) : null;
        for (Document c : list) {
            long extraReviewsCount = 100;
            Object extras = c.get("extras");
            Object group = extras instanceof Document ? ((Document) extras).get("group") : null;
            if (group != null && addReviews != null && addReviews.get(group.toString()) != null) {
                Object v = addReviews.get(group.toString());
                extraReviewsCount = v instanceof Number ? ((Number) v).longValue() : parseLongOr0(v);
            }
            long existing = c.get("totalReviewsOfConsultant") instanceof Number
                    ? ((Number) c.get("totalReviewsOfConsultant")).longValue() : 0;
            c.put("totalReviewsOfConsultant", existing + extraReviewsCount);
        }

        // ---- batch waitlist status (avoids N+1) ----
        List<Object> consultantIds = new ArrayList<>();
        for (Document c : list) consultantIds.add(c.get("_id"));
        Map<String, Object> statusByConsultant = waitlistStatusService.getConsultantStatusBatch(consultantIds);

        List<Document> consultantWaitlistStatus = new ArrayList<>();
        for (Document c : list) {
            String key = c.get("_id") == null ? null : c.get("_id").toString();
            Object status = key == null ? null : statusByConsultant.get(key);
            Document entry = new Document("consultantId", c.get("_id")).append("status", status);
            c.put("_consultantWaitlistStatus", entry);
            consultantWaitlistStatus.add(entry);
        }

        Map<String, Object> userMembership = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(user.getBoolean("isMembership"))) {
            userMembership.put("isMembership", true);
            userMembership.put("planType", user.get("planType"));
            userMembership.put("discountPercentage", user.get("discountPercentage"));
        } else {
            userMembership.put("isMembership", false);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", count);
        out.put("userMembership", userMembership);
        out.put("_consultantWaitlistStatus", consultantWaitlistStatus);
        return out;
    }

    /** Node language-variant expansion (user.js L4285-4308). */
    private List<String> computeMatchLanguages(String language) {
        String langLower = language == null ? "" : language.toLowerCase();
        List<String> m = new ArrayList<>();
        m.add(langLower);
        if (langLower.equals("en") || langLower.equals("english")) m.addAll(Arrays.asList("english", "en", "English"));
        else if (langLower.equals("hi") || langLower.equals("hindi")) m.addAll(Arrays.asList("hindi", "hi", "Hindi"));
        else if (langLower.equals("kn") || langLower.equals("kannada")) m.addAll(Arrays.asList("kannada", "kn", "Kannada"));
        else if (langLower.equals("te") || langLower.equals("telugu")) m.addAll(Arrays.asList("telugu", "te", "Telugu"));
        else if (langLower.equals("ta") || langLower.equals("tamil")) m.addAll(Arrays.asList("tamil", "ta", "Tamil"));
        else if (langLower.equals("mr") || langLower.equals("marathi")) m.addAll(Arrays.asList("marathi", "mr", "Marathi"));

        if (language != null && !language.isEmpty()) {
            String capitalized = Character.toUpperCase(language.charAt(0)) + language.substring(1).toLowerCase();
            if (!m.contains(capitalized)) m.add(capitalized);
            String allLower = language.toLowerCase();
            if (!m.contains(allLower)) m.add(allLower);
            String allUpper = language.toUpperCase();
            if (!m.contains(allUpper)) m.add(allUpper);
        }
        return m;
    }

    private List<Document> parsePlain(String json) {
        return Document.parse("{\"p\":" + json + "}").getList("p", Document.class);
    }

    private String jsonStringArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    private int intOf(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return def;
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return def; }
    }

    private long parseLongOr0(Object v) {
        try { return (long) Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    // ===== verbatim Node pipeline tail (after the dynamic $match), user.js L4351-4451 =====
    private static final String PIPELINE_TAIL = "["
            + "{\"$lookup\":{\"from\":\"waitlists\",\"let\":{\"consultantId\":{\"$toString\":\"$_id\"}},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$eq\":[\"$consultant_id\",\"$$consultantId\"]},\"status\":\"progress\"}},"
            + "  {\"$limit\":1},{\"$project\":{\"_id\":1}}"
            + "],\"as\":\"_activeSessions\"}},"
            + "{\"$addFields\":{"
            + "  \"sortPriority\":{\"$cond\":{\"if\":{\"$or\":[{\"$eq\":[\"$sessionsStatus.isChatLive\",true]},{\"$eq\":[\"$sessionsStatus.isVoiceLive\",true]}]},"
            + "    \"then\":{\"$cond\":{\"if\":{\"$gt\":[{\"$size\":\"$_activeSessions\"},0]},\"then\":1,\"else\":0}},\"else\":2}},"
            + "  \"currentSession\":{\"$size\":\"$_activeSessions\"},"
            + "  \"languagePriority\":{\"$cond\":{\"if\":{\"$cond\":{\"if\":{\"$isArray\":\"$language\"},"
            + "    \"then\":{\"$gt\":[{\"$size\":{\"$setIntersection\":[\"$language\",__MATCH_LANGUAGES__]}},0]},"
            + "    \"else\":{\"$in\":[\"$language\",__MATCH_LANGUAGES__]}}},\"then\":0,\"else\":1}},"
            + "  \"sortRanking\":{\"$ifNull\":[\"$Ranking\",999999]},"
            + "  \"tag\":\"$details.tag\""
            + "}},"
            + "{\"$facet\":{"
            + "\"list\":["
            + "  {\"$sort\":{\"sortPriority\":1,\"languagePriority\":1,\"sortRanking\":1}},"
            + "  {\"$skip\":__SKIP__},{\"$limit\":__LIMIT__},"
            + "  {\"$lookup\":{\"from\":\"review_and_ratings\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "    {\"$match\":{\"$expr\":{\"$eq\":[\"$consultantId\",\"$$consultantId\"]},\"status\":true}},"
            + "    {\"$count\":\"total\"}"
            + "  ],\"as\":\"reviewStats\"}},"
            + "  {\"$addFields\":{\"totalReviewsOfConsultant\":{\"$ifNull\":[{\"$arrayElemAt\":[\"$reviewStats.total\",0]},0]}}},"
            + "  {\"$project\":{\"reviewStats\":0,\"_activeSessions\":0}}"
            + "],"
            + "\"count\":[{\"$count\":\"total\"}]"
            + "}}"
            + "]";
}
