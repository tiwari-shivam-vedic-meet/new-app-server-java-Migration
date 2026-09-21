package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only port of Node POST /v1/user/session/coupon-status, including both legacy fallbacks. */
@Service
public class UserSessionCouponService {

    public record CouponResult(Document data, String message, String version) {}

    private final MongoTemplate mongo;

    public UserSessionCouponService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public CouponResult validate(Document actor, Map<String, Object> input) {
        Object userId = requiredActor(actor);
        String rawCode = firstText(input, "code", "coupon").trim();
        if (rawCode.isBlank()) throw new IllegalArgumentException("Coupon code is required.");

        RuntimeException v2Failure;
        try {
            String context = text(input == null ? null : input.get("context"));
            if (context.isBlank()) context = "CONSULTATION";
            Document data = "RECHARGE".equals(context.toUpperCase(Locale.ROOT))
                    ? validateRecharge(rawCode, number(input, "rechargeBaseAmount"),
                            number(input, "gstAmount"), userId)
                    : validateConsultation(rawCode, text(input == null ? null : input.get("consultantId")), userId);
            return new CouponResult(data, "Coupon is valid.", "v2");
        } catch (RuntimeException error) {
            v2Failure = error;
        }

        Document legacyMaster = mongo.getCollection(Collections.COUPON_MASTERS)
                .find(new Document("code", rawCode).append("isActive", true)).first();
        if (legacyMaster != null) return new CouponResult(legacyMaster, "Offer applied!", "legacy");

        Document systemCoupon = mongo.getCollection(Collections.COUPONS)
                .find(new Document("status", true).append("couponType", "COUPON")
                        .append("couponCode", rawCode))
                .projection(new Document("_id", 1).append("couponType", 1).append("title", 1)
                        .append("couponDiscount", 1).append("couponCode", 1))
                .first();
        if (systemCoupon == null) throw v2Failure;

        Document used = mongo.getCollection(Collections.WAITLISTS).find(
                new Document("user_id", text(userId)).append("coupon._id", systemCoupon.get("_id"))
                        .append("status", "completed"))
                .projection(new Document("_id", 1)).first();
        if (used != null) throw new IllegalStateException("Coupon already used!");
        return new CouponResult(systemCoupon, "Coupon applied!", "legacy");
    }

    private Document validateRecharge(String rawCode, double base, double gst, Object userId) {
        Document coupon = activeV2(rawCode, userId);
        if (!"INFLUENCER".equals(coupon.getString("type"))) {
            throw new IllegalArgumentException(
                    "This coupon cannot be used for recharges. Use an INFLUENCER coupon.");
        }
        if (!"RECHARGE".equals(coupon.getString("appliesOn"))) {
            throw new IllegalArgumentException("This coupon is not applicable on recharges.");
        }
        Document influencer = findById(Collections.INFLUENCERS, coupon.get("influencerId"),
                new Document("isActive", true));
        if (influencer == null) {
            throw new IllegalArgumentException("This coupon is linked to an inactive influencer.");
        }
        double discount = number(coupon.get("discountPercent"));
        double share = number(influencer.get("sharePercent"));
        return new Document("coupon", coupon).append("influencer", influencer)
                .append("discountPercent", discount)
                .append("extraCoins", money(base * discount / 100.0))
                .append("discountAmount", 0.0).append("gstAmount", gst)
                .append("rechargeBaseAmount", base).append("finalAmountPaid", money(base + gst))
                .append("influencerEarning", money(base * share / 100.0))
                .append("influencerSharePercent", share);
    }

    private Document validateConsultation(String rawCode, String consultantId, Object userId) {
        Document coupon = activeV2(rawCode, userId);
        if ("INFLUENCER".equals(coupon.getString("type"))) {
            throw new IllegalArgumentException("This coupon is not valid.");
        }
        if (!"CONSULTATION".equals(coupon.getString("appliesOn"))) {
            throw new IllegalArgumentException("This coupon is not applicable on consultations.");
        }
        if ("CONSULTANT".equals(coupon.getString("type")) && !consultantId.isBlank()
                && !text(coupon.get("consultantId")).equals(consultantId)) {
            throw new IllegalArgumentException("This coupon is not valid for the selected consultant.");
        }
        return new Document("coupon", coupon)
                .append("discountPercent", number(coupon.get("discountPercent")))
                .append("appliesOn", coupon.get("appliesOn"));
    }

    private Document activeV2(String rawCode, Object userId) {
        String normalized = rawCode.toUpperCase(Locale.ROOT).trim();
        Document coupon = mongo.getCollection(Collections.V2_COUPONS)
                .find(new Document("code", normalized).append("isActive", true)).first();
        if (coupon == null) throw new IllegalArgumentException("Coupon not found or inactive.");
        Date now = new Date();
        Date from = coupon.getDate("validFrom");
        Date to = coupon.getDate("validTo");
        if (from != null && now.before(from)) throw new IllegalArgumentException("Coupon is not yet active.");
        if (to != null && now.after(to)) throw new IllegalArgumentException("Coupon has expired.");

        Number usageLimit = coupon.get("usageLimit", Number.class);
        if (usageLimit != null) {
            Document state = mongo.getCollection(Collections.USER_COUPON_STATES).find(
                    new Document("userId", new Document("$in", idVariants(userId)))
                            .append("couponCode", normalized)).first();
            if (integer(state == null ? null : state.get("timesUsed")) >= usageLimit.intValue()) {
                throw new IllegalArgumentException("You have reached the usage limit for this coupon.");
            }
        }
        return coupon;
    }

    private Document findById(String collection, Object value, Document extra) {
        if (value == null) return null;
        Document filter = new Document("_id", new Document("$in", idVariants(value)));
        if (extra != null) filter.putAll(extra);
        return mongo.getCollection(collection).find(filter).first();
    }

    private List<Object> idVariants(Object raw) {
        String value = text(raw);
        if (raw instanceof ObjectId) return List.of(raw, value);
        return ObjectId.isValid(value) ? List.of(raw, new ObjectId(value)) : List.of(raw);
    }

    private Object requiredActor(Document actor) {
        if (actor == null || actor.get("_id") == null) throw new IllegalStateException("Unauthorized");
        return actor.get("_id");
    }
    private String firstText(Map<String, Object> input, String first, String second) {
        if (input == null) return "";
        String value = text(input.get(first));
        return value.isBlank() ? text(input.get(second)) : value;
    }
    private double number(Map<String, Object> input, String key) {
        return number(input == null ? null : input.get(key));
    }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private int integer(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private double money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
