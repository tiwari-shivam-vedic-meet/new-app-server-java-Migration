package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of GET /v2/user/cons/popular (Node rest-apis/modules/user/cons.js L353-760),
 * the consultant "profile" screen payload. Mounted under /v2/user/cons.
 *
 * Returns Node's RAW shape ({@code { success:true, data:{...} }}) — NOT the {code,message,result}
 * envelope — reproduced verbatim so the contract-harness diff matches get-list. On error the
 * controller returns Node's {@code { success:false, message:'Internal server error' }} (HTTP 200).
 *
 * The three heavy reads (offers lookup, review $facet, order-history group) are the exact Node
 * Mongo JSON parsed via {@link #parsePipeline}; the simple finds/counts are programmatic. The two
 * waitlist reads (getProgressingWaitlist(null, consultantId) + getConsultantWaitlist) are delegated
 * to {@link WaitlistStatusService}, which reads the Redis billing clock via {@link TimerReadService}.
 *
 * ⚠ Node quirks preserved deliberately:
 *   - consultantDetails.topConsultantStatus is HARDCODED to "top" (never computed).
 *   - consultantDetails.consAvailable is always 0 (Node: {@code entry?.length > 0 ? 0 : 0}).
 *   - similarConsultant[].rating is HARDCODED to 4 and isFollowed reuses THIS consultant's rel.
 *   - discountPrice == price.default (no offer applied on this screen).
 *   - waitlist queries match consultant_id as a STRING; gallery matches consultantId as a STRING;
 *     offers/review aggregations match _id/consultantId as ObjectId.
 *
 * Diff-pending: enable on /v2 only after the contract-harness diff is clean on the TEST server.
 */
@Service
public class ConsultantPopularService {

    private final MongoTemplate mongo;
    private final AppConstants constants;
    private final WaitlistStatusService waitlistStatus;

    public ConsultantPopularService(MongoTemplate mongo, AppConstants constants,
                                    WaitlistStatusService waitlistStatus) {
        this.mongo = mongo;
        this.constants = constants;
        this.waitlistStatus = waitlistStatus;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> popular(String consultantId, Document user) {
        ObjectId consOid = new ObjectId(consultantId);

        // 1) consultant details + active OFFERS coupons
        List<Document> result = mongo.getCollection(Collections.CONSULTANTS)
                .aggregate(parsePipeline(OFFERS_AGG.replace("__CONS_OID__", consultantId)))
                .into(new ArrayList<>());
        Document consultant = (result != null && !result.isEmpty()) ? result.get(0) : null;

        // 2) similar consultants by primarySkills (Node: consultant?.primarySkills → may be null)
        List<?> primarySkills = consultant == null ? null : consultant.getList("primarySkills", Object.class);
        List<Document> popularConsultants = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", new Document("$ne", consOid))
                        .append("primarySkills", new Document("$in", primarySkills)))
                .limit(4).into(new ArrayList<>());

        // 3) user-consultant relation (userId + consId are both ObjectId in the schema — Node relies
        //    on Mongoose casting the consultantId string; do it explicitly for the native driver)
        Document userConsRel = mongo.getCollection(Collections.USER_CONS_RELS)
                .find(new Document("userId", user.getObjectId("_id")).append("consId", consOid))
                .projection(new Document("isUserFollowed", 1))
                .first();

        // 4) active booking slots
        List<Document> consultantSlotsBook = mongo.getCollection(Collections.CONSULTANT_SLOTS_BOOKS)
                .find(new Document("consultant_id", consOid).append("isActive", true))
                .into(new ArrayList<>());

        // 5) has this user completed a paid (non-FREE5MINUTES) consult with them?
        long isConsultancyTaken = mongo.getCollection(Collections.WAITLISTS)
                .countDocuments(new Document("user_id", user.getObjectId("_id").toHexString())
                        .append("coupon.code", new Document("$ne", "FREE5MINUTES"))
                        .append("consultant_id", consultantId)
                        .append("status", "completed"));

        // 6) live waitlist state (Redis billing clock + waiting queue)
        Document progressingEntry = waitlistStatus.getProgressingWaitlistByConsultant(consultantId);
        List<Document> entry = waitlistStatus.getConsultantWaitlist(consultantId);

        // 7) profile gallery
        List<Document> galleryImages = mongo.getCollection(Collections.GALLERIES)
                .find(new Document("consultantId", consultantId)
                        .append("galleryType", "profile").append("isApprove", true))
                .into(new ArrayList<>());

        // 8) review + rating facet (rating >= 4, longest reviews first)
        List<Document> reviewAndRatingData = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .aggregate(parsePipeline(POPULAR_REVIEW_FACET.replace("__CONS_OID__", consultantId)))
                .into(new ArrayList<>());

        List<Document> giftList = mongo.getCollection(Collections.GIFTS)
                .find(new Document("status", true)).into(new ArrayList<>());

        // 9) order history (completed calls grouped by used_for) + total orders
        List<Document> typeOfConsultDetails = mongo.getCollection(Collections.WAITLISTS)
                .aggregate(parsePipeline(ORDER_HISTORY.replace("__CONS_ID__", consultantId)))
                .into(new ArrayList<>());
        long totalOrders = mongo.getCollection(Collections.WAITLISTS)
                .countDocuments(new Document("consultant_id", consultantId).append("status", "completed"));

        // 10) membership snapshot off the caller doc
        Map<String, Object> userMembership = new LinkedHashMap<>();
        userMembership.put("isMembership", false);
        if (Boolean.TRUE.equals(user.getBoolean("isMembership"))) {
            userMembership.put("isMembership", user.getBoolean("isMembership"));
            userMembership.put("planType", user.get("plan_type"));
            userMembership.put("discountPercentage", user.get("discount_percentage"));
            userMembership.put("consultantId", user.get("consultant_id"));
        }

        List<Document> skillDetails = mongo.getCollection(Collections.SKILLS)
                .find(new Document("status", true))
                .projection(new Document("title", 1).append("image", 1)
                        .append("description", 1).append("bannerImage", 1))
                .into(new ArrayList<>());

        // ---- assemble Node's exact data payload ----
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("consultant", consultant);
        data.put("skillDetails", skillDetails);
        data.put("userConsRel", userConsRel);
        data.put("consultantSlotsBook", consultantSlotsBook);
        data.put("reviewAndRatingData", reviewAndRatingData);
        data.put("giftList", giftList);

        Map<String, Object> orderDetails = new LinkedHashMap<>();
        orderDetails.put("totalOrders", totalOrders);
        orderDetails.put("typeOfConsultDetails", typeOfConsultDetails);
        data.put("orderDetails", orderDetails);
        data.put("userMembership", userMembership);

        Document firstEntry = entry.isEmpty() ? null : entry.get(0);
        Map<String, Object> waitlistBlock = new LinkedHashMap<>();
        waitlistBlock.put("estimatedWaitTime", firstEntry == null ? null : firstEntry.get("totalRequestedTime"));
        waitlistBlock.put("userWaitingNumber", firstEntry == null ? null
                : firstEntry.getList("entries", Document.class, new ArrayList<>()).size());
        waitlistBlock.put("consultantBusy", progressingEntry != null ? progressingEntry.get("remainingTime") : false);
        data.put("waitlist", waitlistBlock);

        boolean isFollowed = userConsRel != null && toBool(userConsRel.get("isUserFollowed"));
        Map<String, Object> consultantDetails = new LinkedHashMap<>();
        consultantDetails.put("_id", consultant.get("_id"));
        consultantDetails.put("name", consultant.get("accountName"));
        consultantDetails.put("isFollowed", isFollowed);
        consultantDetails.put("consAvailable", 0); // Node: entry?.length > 0 ? 0 : 0
        consultantDetails.put("profileImage", withMediaUrl(consultant.getString("profileImage")));
        consultantDetails.put("topConsultantStatus", "top"); // Node hardcodes 'top'
        consultantDetails.put("greenTick", consultant.get("greenTick"));
        Document sessionsStatus = (Document) consultant.get("sessionsStatus");
        consultantDetails.put("isCallLive", sessionsStatus == null ? null : sessionsStatus.get("isVoiceLive"));
        consultantDetails.put("isChatLive", sessionsStatus == null ? null : sessionsStatus.get("isChatLive"));
        consultantDetails.put("isVideoLive", sessionsStatus == null ? null : sessionsStatus.get("isVideoLive"));
        consultantDetails.put("expertise", consultant.get("expertise"));
        consultantDetails.put("language", consultant.get("language"));
        consultantDetails.put("primarySkills", consultant.get("primarySkills"));
        consultantDetails.put("PersonalDetails", consultant.get("details"));
        consultantDetails.put("Video", consultant.get("podcast"));
        consultantDetails.put("Client", consultant.get("clientStories"));
        consultantDetails.put("questionAnswer", consultant.get("questionAnswer"));
        consultantDetails.put("ratingParams", consultant.get("ratingParams"));
        consultantDetails.put("comfortableTopic", consultant.get("topics"));
        consultantDetails.put("city", consultant.get("city"));
        consultantDetails.put("gallery", mapGallery(galleryImages));
        consultantDetails.put("bio", consultant.get("bio"));
        Document price = (Document) consultant.get("price");
        Object priceDefault = price == null ? null : price.get("default");
        consultantDetails.put("price", priceDefault);
        consultantDetails.put("offer", consultant.get("offers") == null ? null : consultant.get("offers"));
        consultantDetails.put("discountPrice", priceDefault);
        consultantDetails.put("experienceYear", consultant.get("experienceYear"));
        consultantDetails.put("isConsultancyTaken", isConsultancyTaken > 0);
        consultantDetails.put("consIsLive", toBool(consultant.get("isOnline")));
        data.put("consultantDetails", consultantDetails);

        List<Map<String, Object>> similar = new ArrayList<>();
        for (Document item : popularConsultants) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("_id", item.get("_id"));
            s.put("name", item.get("accountName"));
            s.put("profileImage", withMediaUrl(item.getString("profileImage")));
            s.put("rating", 4); // Node hardcodes 4
            s.put("isFollowed", isFollowed);
            s.put("consAvailable", 0);
            similar.add(s);
        }
        data.put("similarConsultant", similar);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private List<Map<String, Object>> mapGallery(List<Document> galleryImages) {
        List<Map<String, Object>> gallery = new ArrayList<>();
        if (galleryImages != null) {
            for (Document item : galleryImages) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("_id", item.get("_id"));
                g.put("galleryImage", constants.mediaUrl + item.getString("image"));
                g.put("imageType", item.get("imageType"));
                g.put("createdAt", item.get("createdAt"));
                gallery.add(g);
            }
        }
        return gallery;
    }

    /** Node: profileImage?.startsWith('https') ? profileImage : MEDIA_URL + profileImage. */
    private String withMediaUrl(String profileImage) {
        if (profileImage != null && profileImage.startsWith("https")) return profileImage;
        return constants.mediaUrl + profileImage;
    }

    private boolean toBool(Object v) {
        return v instanceof Boolean && (Boolean) v;
    }

    private List<Document> parsePipeline(String json) {
        Document wrap = Document.parse("{\"p\":" + json.replace("__MEDIA_URL__", constants.mediaUrl) + "}");
        return wrap.getList("p", Document.class);
    }

    // ---- verbatim Node Mongo pipelines ----

    /** consultant.aggregate: $match _id + $lookup active OFFERS coupons (cons.js L357-389). */
    private static final String OFFERS_AGG = "["
            + "{\"$match\":{\"_id\":{\"$oid\":\"__CONS_OID__\"}}},"
            + "{\"$lookup\":{\"from\":\"coupons\",\"let\":{\"consultantId\":\"$_id\"},\"pipeline\":["
            + "  {\"$match\":{\"$expr\":{\"$and\":["
            + "    {\"$in\":[\"$$consultantId\",\"$consultantId\"]},"
            + "    {\"$eq\":[\"$status\",true]},"
            + "    {\"$eq\":[\"$couponType\",\"OFFERS\"]}]}}},"
            + "  {\"$project\":{\"userType\":1,\"couponDiscount\":1}}"
            + "],\"as\":\"offers\"}}"
            + "]";

    /** review_and_ratings.aggregate $facet (cons.js L415-648) — sorted by review length desc. */
    private static final String POPULAR_REVIEW_FACET = "["
            + "{\"$match\":{\"consultantId\":{\"$oid\":\"__CONS_OID__\"},\"status\":true,\"rating\":{\"$gte\":4}}},"
            + "{\"$facet\":{"
            + "\"reviewRatingList\":["
            + "  {\"$addFields\":{\"reviewLength\":{\"$cond\":[{\"$ne\":[\"$review\",null]},{\"$strLenCP\":{\"$ifNull\":[\"$review\",\"\"]}},0]}}},"
            + "  {\"$sort\":{\"reviewLength\":-1,\"createdAt\":-1}},{\"$limit\":4},"
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

    /** waitlist.aggregate order history by used_for (cons.js L653-673). consultant_id is a STRING. */
    private static final String ORDER_HISTORY = "["
            + "{\"$match\":{\"consultant_id\":\"__CONS_ID__\",\"status\":\"completed\"}},"
            + "{\"$group\":{\"_id\":\"$used_for\",\"totalCallMinutes\":{\"$sum\":\"$requested_time\"}}},"
            + "{\"$project\":{\"_id\":0,\"used_for\":\"$_id\",\"totalCallMinutes\":{\"$round\":[\"$totalCallMinutes\",2]}}}"
            + "]";
}
