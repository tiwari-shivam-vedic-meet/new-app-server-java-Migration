package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AdminMembershipDiscountService {

    private static final String COUPON_PREFIX = "COU";
    private static final int COUPON_LENGTH = 3;
    private static final String COUPON_CHARS = "1V2E3D4I5C";

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    @Value("${AWS_S3_BUCKET:vedic-meet-bucket}")
    private String bucket;
    @Value("${AWS_REGION:ap-south-1}")
    private String region;

    public AdminMembershipDiscountService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> list(Map<String, String> query) {
        Map<String, String> input = stringQuery(query);
        Page page = page(input);
        String type = input.get("type");
        String search = input.getOrDefault("search", "");
        String couponType = input.getOrDefault("couponType", "");

        Document project = project(type);
        if ("recharge".equals(type)) {
            project.append("rechargeOfferExpiry", 1);
        }

        List<Document> rows;
        if ("coupon".equals(type)) {
            Document params = new Document("$expr",
                    new Document("$not", new Document("$in", List.of("$couponType", List.of("OFFERS")))));
            if (!couponType.isEmpty()) params.append("couponType", couponType);
            if (!search.isEmpty()) {
                // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:466 builds an unescaped regex from search.
                params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
            }
            rows = aggregate(Collections.COUPONS, listPipeline(params, project, page.skip, page.limit));
        } else {
            Document params = new Document("type", type);
            if (!search.isEmpty()) {
                // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:450 builds an unescaped regex from search.
                params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
            }
            rows = aggregate(Collections.MEMBERSHIP_DISCOUNTS, listPipeline(params, project, page.skip, page.limit));
        }

        return listResultFromFacet(rows);
    }

    public Map<String, Object> add(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        switch (str(input.get("type"))) {
            case "coupon" -> {
                addCoupon(input, Collections.COUPONS, true);
                return null;
            }
            case "membership" -> {
                return addMembership(input);
            }
            case "recharge" -> {
                return addRecharge(input);
            }
            default -> {
                return null;
            }
        }
    }

    public Map<String, Object> addMasterCoupon(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        switch (str(input.get("type"))) {
            case "coupon" -> {
                addMasterCouponDocument(input);
                return null;
            }
            case "membership" -> {
                return addMembership(input);
            }
            case "recharge" -> {
                return addRecharge(input);
            }
            case "master" -> {
                return addMaster(input);
            }
            default -> {
                return null;
            }
        }
    }

    public Map<String, Object> update(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        if (!"coupon".equals(input.get("type"))) {
            Document existing = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                    .find(new Document("_id", support.id(input.get("membershipDiscountId")))).first();
            if (existing == null) throw new IllegalStateException("MEMBERSHIP_DISCOUNT_NOT_EXIST");

            if ("recharge".equals(input.get("type"))) {
                Document duplicate = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).find(new Document("type", input.get("type"))
                        .append("_id", new Document("$nin", List.of(support.id(input.get("membershipDiscountId")))))
                        .append("title", input.get("title"))).first();
                if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");
                if (input.containsKey("rechargeOfferExpiry")) {
                    Object expiry = input.get("rechargeOfferExpiry");
                    if (expiry == null || "".equals(expiry) || "null".equals(expiry)) {
                        input.put("rechargeOfferExpiry", null);
                    } else {
                        input.put("rechargeOfferExpiry", date(expiry, "INVALID_EXPIRY_DATE"));
                    }
                }
            } else {
                Document duplicate = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).find(new Document("type", input.get("type"))
                        .append("_id", new Document("$nin", List.of(support.id(input.get("membershipDiscountId")))))
                        .append("planType", input.get("planType"))).first();
                if (duplicate != null) throw new IllegalArgumentException("MEMBERSHIP_PLAN_TYPE_EXIST");
            }

            Document set = membershipUpdate(input);
            // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:338 uploads from files.membershipDiscountImage; JSON image is treated as the already-uploaded value because the Java signature has no files parameter.
            if (input.containsKey("image")) set.append("image", input.get("image"));
            set.append("updatedAt", new Date());
            Document updated = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).findOneAndUpdate(
                    new Document("_id", support.id(input.get("membershipDiscountId"))),
                    new Document("$set", set),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            return withId(updated);
        }

        Document coupon = mongo.getCollection(Collections.COUPONS)
                .find(new Document("_id", support.id(input.get("membershipDiscountId")))).first();
        if (coupon == null) throw new IllegalStateException("COUPON_NOT_EXIST");

        Document duplicate = mongo.getCollection(Collections.COUPONS).find(new Document("type", input.get("type"))
                .append("_id", new Document("$nin", List.of(support.id(input.get("membershipDiscountId")))))
                .append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");

        Document set = couponUpdate(input);
        // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:358 coupon duplicate query includes non-schema field type.
        if (input.containsKey("image")) set.append("image", input.get("image"));
        set.append("updatedAt", new Date());
        Document updated = mongo.getCollection(Collections.COUPONS).findOneAndUpdate(
                new Document("_id", support.id(input.get("membershipDiscountId"))),
                new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return withId(updated);
    }

    public Map<String, Object> blockUnblock(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        switch (str(input.get("type"))) {
            case "coupon" -> {
                Document updated = mongo.getCollection(Collections.COUPONS).findOneAndUpdate(
                        new Document("_id", support.id(input.get("membershipDiscountId")))
                                .append("couponType", input.get("couponType")),
                        new Document("$set", new Document("status", bool(input.get("status"))).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (updated == null) throw new IllegalStateException("COUPON_NOT_EXIST");
                // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:379 returns undefined after a successful coupon block/unblock.
                return null;
            }
            case "recharge" -> {
                Document recharge = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                        .find(new Document("_id", support.id(input.get("membershipDiscountId")))).first();
                if (recharge == null) throw new IllegalStateException("MEMBERSHIP_DISCOUNT_NOT_EXIST");
                Document set = new Document("status", bool(input.get("status"))).append("updatedAt", new Date());
                if (Boolean.FALSE.equals(bool(input.get("status"))) && recharge.get("rechargeOfferExpiry") != null) {
                    set.append("rechargeOfferExpiry", null);
                }
                Document updated = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).findOneAndUpdate(
                        new Document("_id", support.id(input.get("membershipDiscountId"))),
                        new Document("$set", set),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                return withId(updated);
            }
            default -> {
                return null;
            }
        }
    }

    public Map<String, Object> details(Map<String, String> query) {
        Map<String, String> input = stringQuery(query);
        String type = input.get("type");
        Document project = project(type);
        List<Document> rows;
        if ("coupon".equals(type)) {
            Document exists = mongo.getCollection(Collections.COUPONS)
                    .find(new Document("_id", support.id(input.get("couponId"))).append("couponType", input.get("couponType"))).first();
            if (exists == null) throw new IllegalStateException("MEMBERSHIP_DISCOUNT_NOT_EXIST");
            rows = aggregate(Collections.COUPONS, List.of(
                    new Document("$match", new Document("_id", support.id(input.get("couponId"))).append("couponType", input.get("couponType"))),
                    new Document("$project", project)));
        } else {
            Document exists = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                    .find(new Document("_id", support.id(input.get("membershipDiscountId")))).first();
            if (exists == null) throw new IllegalStateException("MEMBERSHIP_DISCOUNT_NOT_EXIST");
            rows = aggregate(Collections.MEMBERSHIP_DISCOUNTS, List.of(
                    new Document("$match", new Document("_id", support.id(input.get("membershipDiscountId")))),
                    new Document("$project", project)));
        }
        return rows.isEmpty() ? new LinkedHashMap<>() : asMap(rows.get(0));
    }

    public Map<String, Object> listOffers(Map<String, String> query) {
        Map<String, String> input = stringQuery(query);
        Page page = page(input);
        Document params = new Document("couponType", input.get("type"));
        String search = input.getOrDefault("search", "");
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:710 builds an unescaped regex from search.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        FindIterable<Document> iterable = mongo.getCollection(Collections.COUPONS).find(params)
                .sort(new Document("createdAt", -1)).skip(page.skip).limit(page.limit);
        List<Map<String, Object>> list = iterable.into(new ArrayList<>()).stream().map(this::withId).toList();
        long total = mongo.getCollection(Collections.COUPONS).countDocuments(params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> addOffers(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        input.put("couponType", input.get("type"));
        Document duplicate = mongo.getCollection(Collections.COUPONS)
                .find(new Document("couponType", input.get("type")).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");
        mongo.getCollection(Collections.COUPONS).insertOne(couponCreate(input));
        // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:635 creates an offer but returns undefined.
        return null;
    }

    public Map<String, Object> editOffers(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document duplicate = mongo.getCollection(Collections.COUPONS).find(new Document("_id",
                new Document("$nin", List.of(support.id(input.get("offerId"))))).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");
        Document updated = mongo.getCollection(Collections.COUPONS).findOneAndUpdate(
                new Document("_id", support.id(input.get("offerId"))),
                new Document("$set", couponUpdate(input).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("OFFER_NOT_EXIST");
        return withId(updated);
    }

    public Map<String, Object> blockUnblockOffers(Map<String, String> query) {
        Map<String, String> input = stringQuery(query);
        Document updated = mongo.getCollection(Collections.COUPONS).findOneAndUpdate(
                new Document("_id", support.id(input.get("offerId"))),
                new Document("$set", new Document("status", bool(input.get("status"))).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("OFFER_NOT_EXIST");
        return withId(updated);
    }

    List<Document> listPipeline(Document params, Document project, int skipIndex, int limit) {
        return List.of(
                new Document("$match", params),
                new Document("$project", project),
                new Document("$facet", new Document("list", List.of(
                        new Document("$sort", new Document("createdAt", -1)),
                        new Document("$skip", skipIndex),
                        new Document("$limit", limit)))
                        .append("count", List.of(new Document("$count", "total")))));
    }

    private Map<String, Object> addMembership(Map<String, Object> input) {
        Document duplicate = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                .find(new Document("type", input.get("type")).append("planType", input.get("planType"))).first();
        if (duplicate != null) throw new IllegalArgumentException("MEMBERSHIP_PLAN_TYPE_EXIST");
        if (!present(input.get("image"))) throw new IllegalArgumentException("IMAGE_REQUIRE");
        // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:144 requires files.membershipDiscountImage; Java accepts body.image as the already-uploaded path because no files parameter exists.
        Document doc = membershipCreate(input);
        mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).insertOne(doc);
        return withId(doc);
    }

    private Map<String, Object> addRecharge(Map<String, Object> input) {
        Document duplicate = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS)
                .find(new Document("type", input.get("type")).append("title", input.get("title"))).first();
        if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");
        if (present(input.get("rechargeOfferExpiry"))) {
            Date expiry = date(input.get("rechargeOfferExpiry"), "INVALID_EXPIRY_DATE");
            input.put("rechargeOfferExpiry", expiry);
        }
        Document doc = membershipCreate(input);
        mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).insertOne(doc);
        return withId(doc);
    }

    private void addCoupon(Map<String, Object> input, String createCollection, boolean validateDates) {
        if (present(input.get("title"))) {
            Document duplicate = mongo.getCollection(Collections.COUPONS)
                    .find(new Document("title", input.get("title"))).first();
            if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");
        }
        if (!present(input.get("image"))) throw new IllegalArgumentException("IMAGE_REQUIRE");
        // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:110 and :210 require files.couponImage; Java accepts body.image as the already-uploaded path because no files parameter exists.
        if (validateDates) {
            if (present(input.get("couponStartDate"))) input.put("couponStartDate", date(input.get("couponStartDate"), null));
            if (present(input.get("couponEndDate"))) input.put("couponEndDate", date(input.get("couponEndDate"), null));
            Date start = utcMidnight(input.get("couponStartDate"));
            Date end = utcMidnight(input.get("couponEndDate"));
            if (start.getTime() > end.getTime()) throw new IllegalArgumentException("END_GREATER_START");
        }
        input.put("couponCode", generateCouponCode());
        Document doc = couponCreate(input);
        mongo.getCollection(createCollection).insertOne(doc);
    }

    private void addMasterCouponDocument(Map<String, Object> input) {
        if (present(input.get("title"))) {
            Document duplicate = mongo.getCollection(Collections.COUPONS)
                    .find(new Document("title", input.get("title"))).first();
            if (duplicate != null) throw new IllegalArgumentException("TITLE_EXIST");
        }
        if (!present(input.get("image"))) throw new IllegalArgumentException("IMAGE_REQUIRE");
        // FAITHFUL(node-quirk): utils/classes/membership-discount-service.js:215 creates a masters document from coupon-shaped input; strict MasterSchema drops those coupon fields.
        mongo.getCollection(Collections.MASTERS).insertOne(new Document("_id", new ObjectId()));
    }

    private Map<String, Object> addMaster(Map<String, Object> input) {
        int totalMinutes;
        try {
            totalMinutes = Integer.parseInt(str(input.get("couponDuration")));
        } catch (Exception ignored) {
            throw new IllegalArgumentException("INVALID_DURATION");
        }
        Document updated = mongo.getCollection(Collections.MASTERS).findOneAndUpdate(
                new Document("masterCoupon.couponCode", new Document("$exists", true)),
                new Document("$set", new Document("masterCoupon",
                        new Document("couponCode", input.get("title")).append("totalMinutes", totalMinutes))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).upsert(true));
        return asMap(updated);
    }

    private Document project(String type) {
        return switch (str(type)) {
            case "coupon" -> new Document("title", 1).append("status", 1).append("createdAt", 1)
                    .append("couponDiscount", 1).append("couponStartDate", 1).append("couponEndDate", 1)
                    .append("couponType", 1).append("couponCode", 1).append("image", media("$image"));
            case "recharge" -> new Document("title", 1).append("status", 1).append("createdAt", 1)
                    .append("rechargeAmount", 1).append("rechargeCoins", 1).append("rechargeDiscount", 1);
            case "membership" -> new Document("title", 1).append("status", 1).append("createdAt", 1)
                    .append("details", 1).append("membershipPrice", 1).append("membershipDiscountPrice", 1)
                    .append("planType", 1).append("membershipDuration", 1).append("discountPercentage", 1)
                    .append("image", media("$image"));
            default -> new Document();
        };
    }

    private Document membershipCreate(Map<String, Object> input) {
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId());
        membershipUpdate(input).forEach(doc::append);
        if (!doc.containsKey("status")) doc.append("status", true);
        doc.append("createdAt", now).append("updatedAt", now);
        return doc;
    }

    private Document membershipUpdate(Map<String, Object> input) {
        Document doc = new Document();
        putString(doc, input, "type");
        putString(doc, input, "title");
        putString(doc, input, "image");
        putString(doc, input, "planType");
        putString(doc, input, "platform");
        putString(doc, input, "details");
        putNumber(doc, input, "membershipPrice");
        putNumber(doc, input, "membershipDiscountPrice");
        putNumber(doc, input, "discountPercentage");
        putNumber(doc, input, "membershipDuration");
        putNumber(doc, input, "rechargeCoins");
        putNumber(doc, input, "rechargeAmount");
        putNumber(doc, input, "rechargeDiscount");
        if (input.containsKey("rechargeOfferExpiry")) {
            Object raw = input.get("rechargeOfferExpiry");
            doc.put("rechargeOfferExpiry", raw == null ? null : raw instanceof Date ? raw : date(raw, "INVALID_EXPIRY_DATE"));
        }
        if (input.containsKey("status")) doc.put("status", bool(input.get("status")));
        return doc;
    }

    private Document couponCreate(Map<String, Object> input) {
        Date now = new Date();
        Document doc = new Document("_id", new ObjectId());
        couponUpdate(input).forEach(doc::append);
        if (!doc.containsKey("image")) doc.append("image", "");
        if (!doc.containsKey("status")) doc.append("status", true);
        doc.append("createdAt", now).append("updatedAt", now);
        return doc;
    }

    private Document couponUpdate(Map<String, Object> input) {
        Document doc = new Document();
        putString(doc, input, "couponType");
        putString(doc, input, "title");
        putNumber(doc, input, "couponDiscount");
        putDate(doc, input, "couponStartDate");
        putDate(doc, input, "couponEndDate");
        putString(doc, input, "couponCode");
        putString(doc, input, "image");
        if (input.containsKey("status")) doc.put("status", bool(input.get("status")));
        if (input.containsKey("consultantId")) doc.put("consultantId", objectIdList(input.get("consultantId")));
        putString(doc, input, "userType");
        return doc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listResultFromFacet(List<Document> rows) {
        List<?> list = List.of();
        long total = 0;
        if (!rows.isEmpty()) {
            Document facet = rows.get(0);
            list = (List<?>) facet.getOrDefault("list", List.of());
            List<Document> count = (List<Document>) facet.getOrDefault("count", List.of());
            if (!count.isEmpty() && count.get(0).get("total") instanceof Number number) {
                total = number.longValue();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        return mongo.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private Document media(String field) {
        return new Document("$cond", List.of(
                new Document("$eq", List.of(field, "")),
                "",
                new Document("$concat", List.of(mediaUrl(), field))));
    }

    private String mediaUrl() {
        String b = present(bucket) ? bucket : "vedic-meet-bucket";
        String r = present(region) ? region : "ap-south-1";
        return "https://" + b + ".s3." + r + ".amazonaws.com/";
    }

    private Map<String, Object> withId(Document source) {
        if (source == null) return null;
        Map<String, Object> result = asMap(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private Map<String, Object> asMap(Document source) {
        if (source == null) return null;
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }

    private Map<String, String> stringQuery(Map<String, String> query) {
        return query == null ? new LinkedHashMap<>() : new LinkedHashMap<>(query);
    }

    private Page page(Map<String, String> query) {
        int page = intOf(query.getOrDefault("page", "1"), 1);
        int limit = intOf(query.getOrDefault("limit", "10"), 10);
        return new Page(page, limit, (page - 1) * limit);
    }

    private int intOf(Object raw, int fallback) {
        try {
            return Integer.parseInt(str(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void putString(Document target, Map<String, Object> input, String key) {
        if (input.containsKey(key)) target.put(key, str(input.get(key)));
    }

    private void putNumber(Document target, Map<String, Object> input, String key) {
        if (!input.containsKey(key)) return;
        target.put(key, number(input.get(key)));
    }

    private void putDate(Document target, Map<String, Object> input, String key) {
        if (!input.containsKey(key)) return;
        target.put(key, input.get(key) == null ? null : date(input.get(key), null));
    }

    private Number number(Object value) {
        if (value instanceof Number number) return number;
        String text = str(value);
        try {
            double parsed = Double.parseDouble(text);
            if (!text.contains(".") && parsed <= Integer.MAX_VALUE && parsed >= Integer.MIN_VALUE) {
                return (int) parsed;
            }
            return parsed;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        String text = str(value);
        if ("false".equalsIgnoreCase(text)) return false;
        if ("true".equalsIgnoreCase(text)) return true;
        return Boolean.valueOf(text);
    }

    private Date date(Object raw, String error) {
        if (raw instanceof Date date) return date;
        try {
            return Date.from(Instant.parse(str(raw)));
        } catch (Exception first) {
            try {
                return Date.from(LocalDate.parse(str(raw)).atStartOfDay(ZoneId.systemDefault()).toInstant());
            } catch (Exception second) {
                if (error != null) throw new IllegalArgumentException(error);
                throw new IllegalArgumentException("Invalid time value");
            }
        }
    }

    private Date utcMidnight(Object raw) {
        Date parsed = date(raw, null);
        LocalDate local = parsed.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return Date.from(local.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private List<Object> objectIdList(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<Object> out = new ArrayList<>();
        for (Object value : values) out.add(support.id(value));
        return out;
    }

    private String generateCouponCode() {
        StringBuilder random = new StringBuilder();
        for (int i = 0; i < COUPON_LENGTH; i++) {
            random.append(COUPON_CHARS.charAt(ThreadLocalRandom.current().nextInt(COUPON_CHARS.length())));
        }
        String millis = new StringBuilder(String.valueOf(System.currentTimeMillis())).reverse().substring(0, 2);
        return COUPON_PREFIX + random + millis;
    }

    private boolean present(Object value) {
        return value != null && !str(value).isEmpty();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Page(int page, int limit, int skip) {}
}
