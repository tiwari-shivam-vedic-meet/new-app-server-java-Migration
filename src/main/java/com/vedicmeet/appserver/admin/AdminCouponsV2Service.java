package com.vedicmeet.appserver.admin;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminCouponsV2Service {

    private static final Pattern JS_FLOAT_PREFIX =
            Pattern.compile("^[\\t\\n\\r ]*[+-]?(?:(?:\\d+\\.?\\d*)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?");
    private static final Pattern JS_INT_PREFIX = Pattern.compile("^[\\t\\n\\r ]*[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminCouponsV2Service(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Document create(Map<String, Object> body, String adminId) {
        Object code = raw(body, "code");
        Object type = raw(body, "type");
        Object discountPercent = raw(body, "discountPercent");
        Object appliesOn = raw(body, "appliesOn");
        Object validFrom = raw(body, "validFrom");
        Object validTo = raw(body, "validTo");

        // FAITHFUL(node-quirk): coupons-v2.js:26-30 uses one falsy check, so 0/false/blank all fail together.
        if (!truthy(code) || !truthy(type) || !truthy(discountPercent) || !truthy(appliesOn)
                || !truthy(validFrom) || !truthy(validTo)) {
            throw new IllegalArgumentException("code, type, discountPercent, appliesOn, validFrom, validTo are required.");
        }

        Map<String, String> typeAppliesOnMap = new LinkedHashMap<>();
        typeAppliesOnMap.put("INFLUENCER", "RECHARGE");
        typeAppliesOnMap.put("CONSULTANT", "CONSULTATION");
        typeAppliesOnMap.put("GENERAL", "CONSULTATION");
        String expectedAppliesOn = typeAppliesOnMap.get(value(type));
        // FAITHFUL(node-quirk): coupons-v2.js:35-43 unknown types interpolate JavaScript undefined.
        if (!value(appliesOn).equals(expectedAppliesOn)) {
            throw new IllegalArgumentException("Coupon type " + value(type) + " must have appliesOn = "
                    + (expectedAppliesOn == null ? "undefined" : expectedAppliesOn) + ".");
        }

        // FAITHFUL(node-quirk): v2-coupon.model.js:111-124 model pre-validate requires owner ids by type.
        if ("INFLUENCER".equals(value(type)) && !truthy(raw(body, "influencerId"))) {
            throw new IllegalArgumentException("INFLUENCER coupons require an influencerId");
        }
        if ("CONSULTANT".equals(value(type)) && !truthy(raw(body, "consultantId"))) {
            throw new IllegalArgumentException("CONSULTANT coupons require a consultantId");
        }

        Date now = new Date();
        Document coupon = new Document("_id", new ObjectId())
                // FAITHFUL(node-quirk): coupons-v2.js:48 uppercases before trimming.
                .append("code", value(code).toUpperCase(Locale.ENGLISH).trim())
                .append("type", type)
                .append("discountPercent", jsParseFloat(discountPercent))
                .append("appliesOn", appliesOn)
                .append("influencerId", objectIdOrNull(raw(body, "influencerId")))
                .append("consultantId", objectIdOrNull(raw(body, "consultantId")))
                .append("validFrom", jsDate(validFrom))
                .append("validTo", jsDate(validTo))
                .append("minAmount", 0)
                .append("maxAmount", null)
                .append("maxDiscountAmount", null)
                // FAITHFUL(node-quirk): coupons-v2.js:56 parses usageLimit only when truthy, so 0 becomes null.
                .append("usageLimit", truthy(raw(body, "usageLimit")) ? jsParseInt(raw(body, "usageLimit")) : null)
                .append("usageCount", 0)
                .append("isActive", true)
                .append("createdBy", truthy(adminId) ? support.id(adminId) : null)
                .append("description", truthy(raw(body, "description")) ? raw(body, "description") : "")
                .append("consultantShare", 0)
                .append("__v", 0)
                .append("createdAt", now)
                .append("updatedAt", now);

        try {
            mongo.getCollection(Collections.V2_COUPONS).insertOne(coupon);
            return coupon;
        } catch (MongoWriteException error) {
            if (error.getCode() == 11000) {
                throw new IllegalStateException("Coupon code already exists.");
            }
            throw error;
        }
    }

    public Map<String, Object> list(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        Document filter = new Document();
        if (truthy(q.get("type"))) filter.append("type", q.get("type"));
        if (truthy(q.get("appliesOn"))) filter.append("appliesOn", q.get("appliesOn"));
        if (q.containsKey("isActive")) filter.append("isActive", "true".equals(q.get("isActive")));
        if (truthy(q.get("influencerId"))) filter.append("influencerId", support.id(q.get("influencerId")));
        if (truthy(q.get("consultantId"))) filter.append("consultantId", support.id(q.get("consultantId")));
        if (truthy(q.get("search"))) {
            filter.append("$or", List.of(
                    new Document("code", new Document("$regex", q.get("search")).append("$options", "i")),
                    new Document("description", new Document("$regex", q.get("search")).append("$options", "i"))));
        }

        int page = jsParseInt(q.getOrDefault("page", "0"));
        int pageSize = jsParseInt(q.getOrDefault("pageSize", "20"));
        // FAITHFUL(node-quirk): coupons-v2.js:69-91 list pagination is zero-based and unbounded.
        int skip = page * pageSize;
        List<Document> coupons = mongo.getCollection(Collections.V2_COUPONS)
                .find(filter)
                .sort(new Document("createdAt", -1))
                .skip(skip)
                .limit(pageSize)
                .into(new ArrayList<>());
        coupons.replaceAll(this::populateCoupon);
        long total = mongo.getCollection(Collections.V2_COUPONS).countDocuments(filter);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("total", total);
        pagination.put("page", page);
        pagination.put("pageSize", pageSize);
        pagination.put("pageCount", (int) Math.ceil(total / (double) pageSize));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", coupons);
        data.put("pagination", pagination);
        return data;
    }

    public Document single(String id) {
        Document coupon = mongo.getCollection(Collections.V2_COUPONS)
                .find(new Document("_id", support.id(id))).first();
        if (coupon == null) throw new IllegalStateException("Coupon not found.");
        return populateCoupon(coupon);
    }

    public Document update(String id, Map<String, Object> body) {
        Document set = new Document();
        for (String field : List.of("discountPercent", "validFrom", "validTo", "usageLimit", "description", "isActive")) {
            if (body != null && body.containsKey(field)) set.append(field, castUpdate(field, body.get(field)));
        }
        // FAITHFUL(node-quirk): coupons-v2.js:143-149 silently ignores code/type/appliesOn updates.
        set.append("updatedAt", new Date());
        Document coupon = mongo.getCollection(Collections.V2_COUPONS).findOneAndUpdate(
                new Document("_id", support.id(id)),
                new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (coupon == null) throw new IllegalStateException("Coupon not found.");
        return coupon;
    }

    public Object deactivate(String id) {
        // FAITHFUL(node-quirk): coupons-v2.js:162-163 returns success even when no coupon matched.
        mongo.getCollection(Collections.V2_COUPONS).findOneAndUpdate(
                new Document("_id", support.id(id)),
                new Document("$set", new Document("isActive", false).append("updatedAt", new Date())));
        return null;
    }

    public Map<String, Object> validate(Map<String, Object> body) {
        Object code = raw(body, "code");
        Object context = raw(body, "context");
        if (!truthy(code) || !truthy(context)) {
            throw new IllegalArgumentException("code and context (RECHARGE|CONSULTATION) are required.");
        }

        if ("RECHARGE".equals(value(context))) {
            // FAITHFUL(node-quirk): coupons-v2.js:179-184 destructures userId but does not pass it for recharge validation.
            return validateCouponForRecharge(code, jsParseFloat(firstTruthy(raw(body, "rechargeBaseAmount"), 0)),
                    jsParseFloat(firstTruthy(raw(body, "gstAmount"), 0)), null);
        }
        if ("CONSULTATION".equals(value(context))) {
            return validateCouponForConsultation(code, raw(body, "consultantId"), raw(body, "userId"));
        }
        throw new IllegalArgumentException("context must be RECHARGE or CONSULTATION.");
    }

    private Map<String, Object> validateCouponForRecharge(Object rawCode, double rechargeBaseAmount,
                                                          double gstAmount, Object userId) {
        if (!truthy(rawCode)) throw new IllegalArgumentException("No coupon code provided.");
        Document coupon = fetchActiveCoupon(rawCode, userId);
        if (!"INFLUENCER".equals(coupon.getString("type"))) {
            throw new IllegalStateException("This coupon cannot be used for recharges. Use an INFLUENCER coupon.");
        }
        if (!"RECHARGE".equals(coupon.getString("appliesOn"))) {
            throw new IllegalStateException("This coupon is not applicable on recharges.");
        }

        Document influencer = mongo.getCollection(Collections.INFLUENCERS)
                .find(new Document("_id", coupon.get("influencerId")).append("isActive", true)).first();
        if (influencer == null) throw new IllegalStateException("This coupon is linked to an inactive influencer.");

        // FAITHFUL(node-quirk): coupon-v2.service.js:89-97 min/max amount validations are commented out.
        double extraCoins = round2(rechargeBaseAmount * (number(coupon.get("discountPercent")) / 100.0));
        double discountAmount = 0;
        double finalAmountPaid = round2(rechargeBaseAmount + gstAmount);
        double influencerEarning = round2(rechargeBaseAmount * (number(influencer.get("sharePercent")) / 100.0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coupon", coupon);
        result.put("influencer", influencer);
        result.put("discountPercent", coupon.get("discountPercent"));
        result.put("extraCoins", extraCoins);
        result.put("discountAmount", discountAmount);
        result.put("gstAmount", gstAmount);
        result.put("rechargeBaseAmount", rechargeBaseAmount);
        result.put("finalAmountPaid", finalAmountPaid);
        result.put("influencerEarning", influencerEarning);
        result.put("influencerSharePercent", influencer.get("sharePercent"));
        return result;
    }

    private Map<String, Object> validateCouponForConsultation(Object rawCode, Object consultantId, Object userId) {
        if (!truthy(rawCode)) throw new IllegalArgumentException("No coupon code provided.");
        Document coupon = fetchActiveCoupon(rawCode, userId);
        if ("INFLUENCER".equals(coupon.getString("type"))) {
            throw new IllegalStateException("This coupon is not valid.");
        }
        if (!"CONSULTATION".equals(coupon.getString("appliesOn"))) {
            throw new IllegalStateException("This coupon is not applicable on consultations.");
        }

        // FAITHFUL(node-quirk): coupon-v2.service.js:158 only checks consultant ownership when consultantId is truthy.
        if ("CONSULTANT".equals(coupon.getString("type")) && truthy(consultantId)
                && !value(coupon.get("consultantId")).equals(value(consultantId))) {
            throw new IllegalStateException("This coupon is not valid for the selected consultant.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coupon", coupon);
        result.put("discountPercent", coupon.get("discountPercent"));
        result.put("appliesOn", coupon.get("appliesOn"));
        return result;
    }

    private Document fetchActiveCoupon(Object rawCode, Object userId) {
        Document coupon = mongo.getCollection(Collections.V2_COUPONS).find(new Document("code",
                value(rawCode).toUpperCase(Locale.ENGLISH).trim()).append("isActive", true)).first();
        if (coupon == null) throw new IllegalStateException("Coupon not found or inactive.");

        Date now = new Date();
        Date validFrom = dateOrNull(coupon.get("validFrom"));
        Date validTo = dateOrNull(coupon.get("validTo"));
        if (validFrom != null && now.before(validFrom)) throw new IllegalStateException("Coupon is not yet active.");
        if (validTo != null && now.after(validTo)) throw new IllegalStateException("Coupon has expired.");

        // FAITHFUL(node-quirk): coupon-v2.service.js:34 uses !== null, so undefined-style missing values would still enter the branch.
        if (coupon.containsKey("usageLimit") && coupon.get("usageLimit") != null) {
            if (truthy(userId)) {
                Document userState = mongo.getCollection(Collections.USER_COUPON_STATES)
                        .find(new Document("userId", new ObjectId(value(userId)))
                                .append("couponCode", coupon.get("code"))).first();
                double timesUsed = userState == null || !truthy(userState.get("timesUsed")) ? 0 : number(userState.get("timesUsed"));
                if (timesUsed >= number(coupon.get("usageLimit"))) {
                    throw new IllegalStateException("You have reached the usage limit for this coupon.");
                }
            } else if (number(coupon.get("usageCount")) >= number(coupon.get("usageLimit"))) {
                throw new IllegalStateException("Coupon usage limit has been reached.");
            }
        }
        return coupon;
    }

    private Document populateCoupon(Document source) {
        Document coupon = new Document(source);
        coupon.put("influencerId", populate(source.get("influencerId"), Collections.INFLUENCERS,
                List.of("name", "email", "phone", "sharePercent")));
        coupon.put("consultantId", populate(source.get("consultantId"), Collections.CONSULTANTS,
                List.of("accountName", "profileImage")));
        return coupon;
    }

    private Document populate(Object id, String collection, List<String> fields) {
        if (id == null) return null;
        Document found = mongo.getCollection(collection).find(new Document("_id", support.id(id))).first();
        if (found == null) return null;
        Document projected = new Document("_id", found.get("_id"));
        for (String field : fields) projected.append(field, found.get(field));
        return projected;
    }

    private Object castUpdate(String field, Object value) {
        return switch (field) {
            case "discountPercent" -> jsParseFloat(value);
            case "validFrom", "validTo" -> jsDate(value);
            case "usageLimit" -> value == null ? null : jsParseInt(value);
            case "isActive" -> value instanceof Boolean b ? b : Boolean.parseBoolean(value(value));
            default -> value;
        };
    }

    private Object objectIdOrNull(Object value) {
        return truthy(value) ? support.id(value) : null;
    }

    private static Object raw(Map<String, ?> input, String key) {
        return input == null ? null : input.get(key);
    }

    private static Object firstTruthy(Object first, Object fallback) {
        return truthy(first) ? first : fallback;
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0 && !Double.isNaN(n.doubleValue());
        return !String.valueOf(value).isEmpty();
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value(value));
    }

    private static int jsParseInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        Matcher matcher = JS_INT_PREFIX.matcher(value(value));
        if (!matcher.find()) return 0;
        return new BigDecimal(matcher.group().trim()).intValue();
    }

    private static double jsParseFloat(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        Matcher matcher = JS_FLOAT_PREFIX.matcher(value(value));
        if (!matcher.find()) return Double.NaN;
        return Double.parseDouble(matcher.group().trim());
    }

    private static Date jsDate(Object value) {
        if (value instanceof Date d) return d;
        if (value instanceof Number n) return new Date(n.longValue());
        String text = value(value);
        try {
            return Date.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            try {
                return Date.from(Instant.parse(text + "T00:00:00Z"));
            } catch (DateTimeParseException ignoredAgain) {
                return new Date(0);
            }
        }
    }

    private static Date dateOrNull(Object value) {
        if (value instanceof Date d) return d;
        return null;
    }

    private static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
