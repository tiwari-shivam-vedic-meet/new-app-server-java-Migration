package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node MembershipDiscountService.list + details
 * (utils/classes/membership-discount-service.js), served by GET /v1/membership-discount
 * and /details (authed: user OR consultant).
 *
 * Type-specific projections (membership/coupon/recharge) match getProjectData; the coupon
 * list reads the coupons collection excluding OFFERS, everything else reads
 * membership_discounts. Same cache keys/TTLs (6h) and { list, total } / single-doc shapes.
 */
@Service
public class MembershipDiscountService {

    private static final long TTL = 6 * 60 * 60;

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;

    public MembershipDiscountService(MongoTemplate mongo, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> list(String type, Integer pageParam, Integer limitParam, String search, String couponType) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        int skipIndex = (page - 1) * limit;
        String searchStr = search == null ? "" : search;
        String couponTypeStr = couponType == null ? "" : couponType;

        String cacheKey = searchStr.isEmpty()
                ? "membership:discount:list:" + type + ":" + (couponTypeStr.isEmpty() ? "all" : couponTypeStr) + ":" + page + ":" + limit
                : null;
        if (cacheKey != null) {
            Map<String, Object> cached = cache.get(cacheKey, Map.class);
            if (cached != null) return cached;
        }

        Document project = getProjectData(type);
        if ("recharge".equals(type)) {
            project.append("rechargeOfferExpiry", 1);
        }

        List<Document> facetResult;
        List<Document> facetTail = Arrays.asList(
                new Document("$project", project),
                new Document("$facet", new Document()
                        .append("list", Arrays.asList(
                                new Document("$sort", new Document("createdAt", -1)),
                                new Document("$skip", skipIndex),
                                new Document("$limit", limit)))
                        .append("count", Arrays.asList(new Document("$count", "total")))));

        if ("coupon".equals(type)) {
            Document query = new Document("$expr", new Document("$not",
                    new Document("$in", Arrays.asList("$couponType", Arrays.asList("OFFERS")))));
            if (!couponTypeStr.isEmpty()) query.append("couponType", couponTypeStr);
            if (!searchStr.isEmpty()) {
                query.append("title", new Document("$regex", ".*" + searchStr + ".*").append("$options", "i"));
            }
            List<Document> pipeline = new ArrayList<>();
            pipeline.add(new Document("$match", query));
            pipeline.addAll(facetTail);
            facetResult = mongo.getCollection(Collections.COUPONS).aggregate(pipeline).into(new ArrayList<>());
        } else {
            Document params = new Document("type", type);
            if (!searchStr.isEmpty()) {
                params.append("title", new Document("$regex", ".*" + searchStr + ".*").append("$options", "i"));
            }
            List<Document> pipeline = new ArrayList<>();
            pipeline.add(new Document("$match", params));
            pipeline.addAll(facetTail);
            facetResult = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).aggregate(pipeline).into(new ArrayList<>());
        }

        List<Document> outList = new ArrayList<>();
        long total = 0;
        if (!facetResult.isEmpty()) {
            Document f = facetResult.get(0);
            List<Document> l = (List<Document>) f.get("list");
            if (l != null) outList = l;
            List<Document> count = (List<Document>) f.get("count");
            if (count != null && !count.isEmpty()) {
                Number t = count.get(0).get("total", Number.class);
                total = t == null ? 0 : t.longValue();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", outList);
        result.put("total", total);
        if (cacheKey != null) cache.set(cacheKey, result, TTL);
        return result;
    }

    public Object details(String type, String couponId, String membershipDiscountId, String couponType) {
        String idPart = couponId != null ? couponId : membershipDiscountId;
        String cacheKey = "membership:discount:details:" + type + ":" + idPart;
        Document cached = cache.get(cacheKey, Document.class);
        if (cached != null) return cached;

        Document project = getProjectData(type);
        List<Document> details;

        if ("coupon".equals(type)) {
            Document exist = mongo.getCollection(Collections.COUPONS)
                    .find(new Document("_id", new ObjectId(couponId)).append("couponType", couponType)).first();
            if (exist == null) throw new RuntimeException("MEMBERSHIP_DISCOUNT_NOT_EXIST");
            details = mongo.getCollection(Collections.COUPONS).aggregate(Arrays.asList(
                    new Document("$match", new Document("_id", new ObjectId(couponId)).append("couponType", couponType)),
                    new Document("$project", project))).into(new ArrayList<>());
        } else {
            Document exist = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                    .find(new Document("_id", new ObjectId(membershipDiscountId))).first();
            if (exist == null) throw new RuntimeException("MEMBERSHIP_DISCOUNT_NOT_EXIST");
            details = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).aggregate(Arrays.asList(
                    new Document("$match", new Document("_id", new ObjectId(membershipDiscountId))),
                    new Document("$project", project))).into(new ArrayList<>());
        }

        Object result = (details != null && !details.isEmpty()) ? details.get(0) : details;
        cache.set(cacheKey, result, TTL);
        return result;
    }

    /** Mirrors getProjectData(type). */
    private Document getProjectData(String type) {
        Document imageCond = new Document("$cond", Arrays.asList(
                new Document("$eq", Arrays.asList("$image", "")), "",
                new Document("$concat", Arrays.asList(constants.mediaUrl, "$image"))));
        switch (type == null ? "" : type) {
            case "membership":
                return new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("details", 1).append("membershipPrice", 1).append("membershipDiscountPrice", 1)
                        .append("planType", 1).append("membershipDuration", 1).append("discountPercentage", 1)
                        .append("image", imageCond);
            case "coupon":
                return new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("couponDiscount", 1).append("couponStartDate", 1).append("couponEndDate", 1)
                        .append("couponType", 1).append("couponCode", 1).append("image", imageCond);
            case "recharge":
                return new Document("title", 1).append("status", 1).append("createdAt", 1)
                        .append("rechargeAmount", 1).append("rechargeCoins", 1).append("rechargeDiscount", 1);
            default:
                return new Document();
        }
    }
}
