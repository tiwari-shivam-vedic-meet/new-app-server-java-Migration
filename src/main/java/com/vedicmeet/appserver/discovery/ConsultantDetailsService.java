package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.pricing.OfferService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node UserService.consultantDetails (utils/classes/user.js L4866-5517),
 * served by GET /v2/user/consultant_details. Returns the standard envelope
 * ({@code ApiResponse.ok("Consultant details fetched successfully", result)}).
 *
 * The five aggregations are the exact Node Mongo JSON, parsed via {@link #parsePipeline}
 * with placeholders substituted (__MEDIA_URL__, __CONS_OID__, __USER_OID__,
 * __CONS_HAS_30_DAYS_DATE__ extended-JSON date, __EMERGENCY_TIMES__). Per-consultant
 * ranking + pricing reuse the already-ported {@link ConsultantRankingService} and
 * {@link OfferService}.
 *
 * ⚠ Node quirks preserved deliberately:
 *   - topConsultant is called with NO score (score-less overload → NaN parity).
 *   - the "similar consultant" dedup maps JSON.stringify(consultantId) (the REQUEST id,
 *     not element._id), so uniqueSimilarConsultant never actually filters anything.
 *   - expertise is added to `params` (unused by the similar query) — so it never filters similar.
 *
 * Diff-pending: enable on /v2 only after the contract-harness diff is clean on the TEST server.
 */
@Service
public class ConsultantDetailsService {

    private final MongoTemplate mongo;
    private final AppConstants constants;
    private final OfferService offerService;
    private final ConsultantRankingService rankingService;
    private final double emergencyTimes;

    public ConsultantDetailsService(MongoTemplate mongo, AppConstants constants,
                                    OfferService offerService, ConsultantRankingService rankingService,
                                    @Value("${CONSULTANT_EMERGENCY_CALL_CHARGE_TIMES:1.5}") double emergencyTimes) {
        this.mongo = mongo;
        this.constants = constants;
        this.offerService = offerService;
        this.rankingService = rankingService;
        this.emergencyTimes = emergencyTimes;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> consultantDetails(String consultantId, Document user) {
        // Pre-check (Node: findOne({_id: consultantId}) — Mongoose casts the string to ObjectId).
        Document consExist = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", new ObjectId(consultantId))).first();
        if (consExist == null) throw new RuntimeException("CONSULTANT_NOT_EXIST");
        if (!Boolean.TRUE.equals(consExist.getBoolean("status"))) throw new RuntimeException("CONSULTANT_BLOCKED");

        String userOid = user.getObjectId("_id").toHexString();
        String consHas30DaysDate = Instant.now().minus(360, ChronoUnit.DAYS).toString();

        // --- 1) main details aggregation ---
        String mainJson = MAIN_DETAILS
                .replace("__CONS_OID__", consultantId)
                .replace("__USER_OID__", userOid)
                .replace("__CONS_HAS_30_DAYS_DATE__", consHas30DaysDate)
                .replace("__EMERGENCY_TIMES__", trimNum(emergencyTimes));
        List<Document> consultantDetails = mongo.getCollection(Collections.CONSULTANTS)
                .aggregate(parsePipeline(mainJson)).into(new ArrayList<>());

        List<Document> giftList = mongo.getCollection(Collections.GIFTS)
                .find(new Document("status", true)).into(new ArrayList<>());

        // --- 2) similar consultants: dynamic $match built from the first result ---
        Document query = new Document("status", true).append("isDeleted", false).append("isAdminVerify", true);
        if (!consultantDetails.isEmpty()) {
            Document d0 = consultantDetails.get(0);
            List<Object> lang = d0.getList("language", Object.class, new ArrayList<>());
            if (lang != null && !lang.isEmpty()) query.append("language", new Document("$in", lang));
            List<Object> pskills = d0.getList("primarySkills", Object.class, new ArrayList<>());
            if (pskills != null && !pskills.isEmpty()) query.append("primarySkills", new Document("$in", pskills));
            // NOTE: Node adds expertise to `params`, not `query` — so it never filters similar. Preserved by omission.
        }

        List<Document> similarPipeline = new ArrayList<>();
        similarPipeline.add(new Document("$match", query));
        similarPipeline.addAll(parsePipeline(SIMILAR_TAIL)); // ratingQuery + project + sort + limit
        List<Document> similarConsultant = mongo.getCollection(Collections.CONSULTANTS)
                .aggregate(similarPipeline).into(new ArrayList<>());

        // Node dedup bug: compares against JSON.stringify(consultantId) (request id), so nothing is removed.
        List<Document> uniqueSimilarConsultant = new ArrayList<>();
        for (Document c : similarConsultant) {
            if (!jsonId(c.get("_id")).equals(jsonId(consultantId))) {
                uniqueSimilarConsultant.add(c);
            }
        }

        // --- 3) per-consultant topConsultant + offer pricing (reuse ported services) ---
        enrichRankingAndPricing(consultantDetails, user);
        enrichRankingAndPricing(uniqueSimilarConsultant, user);

        // --- 4) review & rating facet ---
        String reviewJson = REVIEW_FACET.replace("__CONS_OID__", consultantId);
        List<Document> reviewAndRatingData = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .aggregate(parsePipeline(reviewJson)).into(new ArrayList<>());

        // --- 5) order history + totals ---
        String orderJson = ORDER_HISTORY.replace("__CONS_OID__", consultantId);
        List<Document> typeOfConsultDetails = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .aggregate(parsePipeline(orderJson)).into(new ArrayList<>());
        long totalOrders = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).countDocuments(
                new Document("consultantId", new ObjectId(consultantId)).append("isConsultantCompleted", "complete"));

        // user membership (from the membership-augmented user doc)
        Map<String, Object> userMembership = new LinkedHashMap<>();
        userMembership.put("isMembership", false);
        if (Boolean.TRUE.equals(user.getBoolean("isMembership"))) {
            userMembership.put("isMembership", true);
            userMembership.put("planType", user.get("planType"));
            userMembership.put("discountPercentage", user.get("discountPercentage"));
            userMembership.put("consultantId", user.get("consultantId"));
        }

        Document broadcastCons = mongo.getCollection(Collections.BROADCASTS)
                .find(new Document("consultantId", new ObjectId(consultantId)).append("status", 1)).first();
        boolean consIsLive = broadcastCons != null;

        Map<String, Object> orderDetails = new LinkedHashMap<>();
        orderDetails.put("totalOrders", totalOrders);
        orderDetails.put("typeOfConsultDetails", typeOfConsultDetails);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consultantDetails", consultantDetails);
        result.put("giftList", giftList);
        result.put("similarConsultant", uniqueSimilarConsultant);
        result.put("reviewAndRatingData", reviewAndRatingData);
        result.put("orderDetails", orderDetails);
        result.put("userMembership", userMembership);
        result.put("consIsLive", consIsLive);
        return result;
    }

    /** topConsultantStatus + discountPrice per element (Node loops L5194-5231). */
    private void enrichRankingAndPricing(List<Document> list, Document user) {
        for (Document element : list) {
            ObjectId elId = element.getObjectId("_id");
            element.put("topConsultantStatus", rankingService.topConsultant(elId.toHexString())); // score-less
            Number price = element.get("price", Number.class);
            Map<String, Object> offer = offerService.getConsOfferDiscountForUser(
                    user.getObjectId("_id"), elId, price);
            boolean status = Boolean.TRUE.equals(offer.get("status"));
            element.put("discountPrice", status ? offer.get("discountPrice") : price);
        }
    }

    private List<Document> parsePipeline(String json) {
        Document wrap = Document.parse("{\"p\":" + json.replace("__MEDIA_URL__", constants.mediaUrl) + "}");
        return wrap.getList("p", Document.class);
    }

    private String jsonId(Object id) {
        return "\"" + String.valueOf(id) + "\"";
    }

    /** Emit an integer without a trailing .0 so $multiply/emergencyCallTimes matches Node's Number(). */
    private String trimNum(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ===== verbatim Node aggregation pipelines (utils/classes/user.js) =====

    private static final String MAIN_DETAILS = "["
            + "{\"$match\":{\"_id\":{\"$oid\":\"__CONS_OID__\"},\"status\":true,\"isDeleted\":false,\"isAdminVerify\":true}},"
            + "{\"$lookup\":{\"from\":\"galleries\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$and\":[{\"$eq\":[\"$consultantId\",\"$$consultantId\"]},{\"$eq\":[\"$isApprove\",true]}]}}},"
            + "  {\"$sort\":{\"createdAt\":-1}},"
            + "  {\"$project\":{\"_id\":0,\"galleryImage\":{\"$cond\":[{\"$eq\":[\"$image\",\"\"]},\"\",{\"$concat\":[\"__MEDIA_URL__\",\"$image\"]}]}}}"
            + "],\"as\":\"gallery\"}},"
            + "{\"$lookup\":{\"from\":\"follows\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$and\":[{\"$eq\":[\"$$consultantId\",\"$consultantId\"]},{\"$eq\":[\"$userId\",{\"$oid\":\"__USER_OID__\"}]},{\"$eq\":[\"$status\",true]}]}}}"
            + "],\"as\":\"followByUser\"}},"
            + "{\"$lookup\":{\"from\":\"consultant_boosts\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$eq\":[\"$$consultantId\",\"$consultantId\"]}}}"
            + "],\"as\":\"boostProfile\"}},"
            + "{\"$unwind\":{\"path\":\"$boostProfile\",\"preserveNullAndEmptyArrays\":true}},"
            + "{\"$lookup\":{\"from\":\"consultantformrequests\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$and\":[{\"$eq\":[\"$$consultantId\",\"$consultantId\"]},{\"$eq\":[\"$userId\",{\"$oid\":\"__USER_OID__\"}]},{\"$eq\":[\"$isConsultantCompleted\",\"complete\"]}]}}}"
            + "],\"as\":\"orderExist\"}},"
            + "{\"$addFields\":{\"isConsHas30Days\":{\"$cond\":{\"if\":{\"$gte\":[\"$createdAt\",{\"$date\":\"__CONS_HAS_30_DAYS_DATE__\"}]},\"then\":false,\"else\":true}}}},"
            + "{\"$addFields\":{\"emergencyCallPrice\":{\"$multiply\":[\"$price\",__EMERGENCY_TIMES__]}}},"
            + "{\"$project\":{"
            + "  \"name\":{\"$cond\":[{\"$eq\":[\"$userName\",\"\"]},\"$name\",\"$userName\"]},"
            + "  \"userId\":1,\"countryCode\":1,\"mobile\":1,\"email\":1,\"gender\":1,\"consType\":1,"
            + "  \"profileImage\":{\"$cond\":{\"if\":{\"$ne\":[\"$image\",\"\"]},\"then\":{\"$concat\":[\"__MEDIA_URL__\",\"$image\"]},\"else\":\"\"}},"
            + "  \"createdAt\":1,\"status\":1,\"isAdminVerify\":1,\"language\":1,\"expertise\":1,\"primarySkills\":1,\"otherSkills\":1,"
            + "  \"gallery\":1,\"price\":1,\"boostProfile\":1,\"emergencyCall\":1,\"isConsHas30Days\":1,\"emergencyCallPrice\":1,"
            + "  \"greenTick\":1,\"isOnline\":1,\"bio\":1,\"experienceYear\":1,\"isChatLive\":1,\"isCallLive\":1,\"isVideoLive\":1,"
            + "  \"isFake\":1,\"score\":1,\"emergencyCallTimes\":__EMERGENCY_TIMES__,"
            + "  \"isFollowed\":{\"$cond\":{\"if\":{\"$eq\":[{\"$size\":\"$followByUser\"},0]},\"then\":false,\"else\":true}},"
            + "  \"isConsultancyTaken\":{\"$cond\":{\"if\":{\"$eq\":[{\"$size\":\"$orderExist\"},0]},\"then\":false,\"else\":true}}"
            + "}}"
            + "]";

    private static final String SIMILAR_TAIL = "["
            + "{\"$lookup\":{\"from\":\"review_and_ratings\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$eq\":[\"$$consultantId\",\"$consultantId\"]}}},"
            + "  {\"$group\":{\"_id\":null,\"average\":{\"$avg\":\"$rating\"}}},"
            + "  {\"$project\":{\"_id\":0,\"average\":{\"$round\":[\"$average\",1]}}}"
            + "],\"as\":\"consRating\"}},"
            + "{\"$project\":{"
            + "  \"name\":{\"$cond\":[{\"$eq\":[\"$userName\",\"\"]},\"$name\",\"$userName\"]},"
            + "  \"countryCode\":1,\"mobile\":1,\"email\":1,\"gender\":1,\"consType\":1,"
            + "  \"profileImage\":{\"$cond\":{\"if\":{\"$ne\":[\"$image\",\"\"]},\"then\":{\"$concat\":[\"__MEDIA_URL__\",\"$image\"]},\"else\":\"\"}},"
            + "  \"createdAt\":1,\"status\":1,\"isAdminVerify\":1,\"language\":1,\"expertise\":1,\"primarySkills\":1,\"otherSkills\":1,"
            + "  \"price\":1,\"isChatLive\":1,\"isCallLive\":1,\"isVideoLive\":1,\"score\":1,"
            + "  \"rating\":{\"$cond\":{\"if\":{\"$eq\":[{\"$size\":\"$consRating\"},0]},\"then\":0,\"else\":{\"$avg\":\"$consRating.average\"}}}"
            + "}},"
            + "{\"$sort\":{\"createdAt\":-1,\"score:\":-1}},"
            + "{\"$limit\":5}"
            + "]";

    private static final String REVIEW_FACET = "["
            + "{\"$match\":{\"consultantId\":{\"$oid\":\"__CONS_OID__\"},\"status\":true,\"rating\":{\"$gte\":4}}},"
            + "{\"$facet\":{"
            + "\"reviewRatingList\":["
            + "  {\"$sort\":{\"createdAt\":-1}},{\"$limit\":4},"
            + "  {\"$lookup\":{\"from\":\"users\",\"let\":{\"userId\":\"$userId\"},\"pipeline\":["
            + "    {\"$match\":{\"$expr\":{\"$and\":[{\"$ne\":[\"$$userId\",null]},{\"$eq\":[\"$$userId\",\"$_id\"]}]}}},"
            + "    {\"$project\":{\"name\":1,\"profileImage\":{\"$cond\":[{\"$or\":[{\"$eq\":[\"$profileImage\",\"\"]},{\"$eq\":[\"$profileImage\",null]}]},\"\",{\"$concat\":[\"__MEDIA_URL__\",\"$profileImage\"]}]}}},"
            + "    {\"$limit\":1}],\"as\":\"userDetails\"}},"
            + "  {\"$unwind\":{\"path\":\"$userDetails\",\"preserveNullAndEmptyArrays\":true}},"
            + "  {\"$lookup\":{\"from\":\"review_replies\",\"let\":{\"reviewRatingId\":\"$_id\",\"consultantId\":\"$consultantId\"},\"pipeline\":["
            + "    {\"$match\":{\"$expr\":{\"$eq\":[\"$$reviewRatingId\",\"$reviewRatingId\"]}}},"
            + "    {\"$lookup\":{\"from\":\"consultants\",\"let\":{\"consultantId\":\"$$consultantId\"},\"pipeline\":["
            + "      {\"$match\":{\"$expr\":{\"$eq\":[\"$$consultantId\",\"$_id\"]}}},"
            + "      {\"$project\":{\"name\":{\"$cond\":[{\"$or\":[{\"$eq\":[\"$userName\",\"\"]},{\"$eq\":[\"$userName\",null]}]},\"$name\",\"$userName\"]},\"profileImage\":{\"$cond\":[{\"$or\":[{\"$eq\":[\"$image\",\"\"]},{\"$eq\":[\"$image\",null]}]},\"\",{\"$concat\":[\"__MEDIA_URL__\",\"$image\"]}]}}},"
            + "      {\"$limit\":1}],\"as\":\"consultantDetails\"}},"
            + "    {\"$unwind\":{\"path\":\"$consultantDetails\",\"preserveNullAndEmptyArrays\":true}},"
            + "    {\"$project\":{\"reply\":1,\"consultantDetails\":1,\"createdAt\":1}},"
            + "    {\"$limit\":1}],\"as\":\"reviewReplyArray\"}},"
            + "  {\"$unwind\":{\"path\":\"$reviewReplyArray\",\"preserveNullAndEmptyArrays\":true}},"
            + "  {\"$addFields\":{"
            + "    \"displayName\":{\"$cond\":[{\"$eq\":[\"$isAdminCreated\",true]},\"$userName\",{\"$ifNull\":[\"$userDetails.name\",\"---\"]}]},"
            + "    \"displayImage\":{\"$cond\":[{\"$and\":[{\"$eq\":[\"$isAdminCreated\",true]},{\"$ne\":[\"$userImage\",\"\"]},{\"$ne\":[\"$userImage\",null]}]},{\"$concat\":[\"__MEDIA_URL__\",\"$userImage\"]},{\"$ifNull\":[\"$userDetails.profileImage\",\"\"]}]},"
            + "    \"displayDate\":{\"$cond\":[{\"$ne\":[\"$customDate\",null]},\"$customDate\",\"$createdAt\"]},"
            + "    \"reviewReply\":{\"$cond\":[{\"$ne\":[\"$reviewReplyArray\",null]},\"$reviewReplyArray\",null]}"
            + "  }},"
            + "  {\"$project\":{\"userDetails\":{\"name\":\"$displayName\",\"profileImage\":\"$displayImage\"},\"review\":1,\"rating\":1,\"createdAt\":1,\"reviewReply\":1}}"
            + "],"
            + "\"averageRatingWithTotal\":["
            + "  {\"$group\":{\"_id\":\"$rating\",\"count\":{\"$sum\":1}}},"
            + "  {\"$group\":{\"_id\":null,\"ratings\":{\"$push\":{\"rating\":\"$_id\",\"count\":\"$count\"}},\"totalRatings\":{\"$sum\":\"$count\"}}},"
            + "  {\"$project\":{\"_id\":0,\"ratings\":1,\"totalRatings\":1}},"
            + "  {\"$unwind\":\"$ratings\"}"
            + "],"
            + "\"averageRating\":["
            + "  {\"$group\":{\"_id\":null,\"average\":{\"$avg\":\"$rating\"}}},"
            + "  {\"$project\":{\"_id\":0,\"average\":{\"$round\":[\"$average\",1]}}}"
            + "]"
            + "}}"
            + "]";

    private static final String ORDER_HISTORY = "["
            + "{\"$match\":{\"consultantId\":{\"$oid\":\"__CONS_OID__\"},\"isConsultantCompleted\":\"complete\"}},"
            + "{\"$group\":{\"_id\":\"$typeOfConsult\",\"totalCallMinutes\":{\"$sum\":\"$totalUserUsedCallMinutes\"}}},"
            + "{\"$project\":{\"_id\":0,\"typeOfConsult\":\"$_id\",\"totalCallMinutes\":{\"$round\":[\"$totalCallMinutes\",2]}}}"
            + "]";
}
