package com.vedicmeet.appserver.consultant;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.banner.BoostTimeService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.crypto.CryptoUtil;
import com.vedicmeet.appserver.session.SessionBookingStore;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Actor-scoped writes for legacy consultant routes. All callers remain behind @MigrationWrite. */
@Service
public class ConsultantLegacyWriteService {

    private final MongoTemplate mongo;
    private final BoostTimeService boostTime;
    private final CryptoUtil crypto;
    private final SessionBookingStore bookings;

    public ConsultantLegacyWriteService(MongoTemplate mongo, BoostTimeService boostTime,
                                        CryptoUtil crypto, SessionBookingStore bookings) {
        this.mongo = mongo;
        this.boostTime = boostTime;
        this.crypto = crypto;
        this.bookings = bookings;
    }

    /**
     * Intended implementation of Node GET /boost. The Node controller forgets to pass req.user;
     * Java fixes that deterministic NEED_GREEN_TICK failure while preserving the toggle behavior.
     */
    public Document boost(Document actor, String rawType) {
        ObjectId consultantId = actorId(actor);
        if (!Boolean.TRUE.equals(actor.get("greenTick"))) throw new IllegalStateException("NEED_GREEN_TICK");
        String type = text(rawType).toUpperCase(Locale.ROOT);
        if (!List.of("CALL", "CHAT", "VIDEO").contains(type)) {
            throw new IllegalArgumentException("BOOST_TYPE_REQUIRE");
        }
        Date start = Date.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC));
        Date end = Date.from(LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        if (mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).find(
                new Document("consultantId", consultantId).append("isConsultantCompleted", "waiting")
                        .append("createdAt", new Document("$gte", start).append("$lt", end))).first() != null) {
            throw new IllegalStateException("BOOST_WALITLIST_NOT_EMPTY");
        }

        Document existing = mongo.getCollection(Collections.CONSULTANT_BOOSTS)
                .find(new Document("consultantId", consultantId)).first();
        if (existing == null) {
            Date now = new Date();
            Document created = flags(type).append("_id", new ObjectId()).append("consultantId", consultantId)
                    .append("lastBoostDate", start).append("startTime", boostTime.currentTime())
                    .append("totalBoostDuration", 0).append("createdAt", now).append("updatedAt", now);
            mongo.getCollection(Collections.CONSULTANT_BOOSTS).insertOne(created);
            return created;
        }

        Document today = mongo.getCollection(Collections.CONSULTANT_BOOSTS).find(
                new Document("_id", existing.get("_id")).append("lastBoostDate",
                        new Document("$gte", start).append("$lt", end))).first();
        if (today != null) return boostTime.updateTimeDuration(today, type);

        Document set = flags(type).append("lastBoostDate", start).append("totalBoostDuration", 0)
                .append("startTime", boostTime.currentTime()).append("updatedAt", new Date());
        return mongo.getCollection(Collections.CONSULTANT_BOOSTS).findOneAndUpdate(
                new Document("_id", existing.get("_id")), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /**
     * Legacy intake-form workflow. It intentionally writes consultantformrequests (not the newer
     * waitlists collection) because old mobile clients consume this exact document shape.
     */
    public Document createIntake(Document user, Map<String, Object> request) {
        ObjectId userId = actorId(user);
        String consultantText = required(request, "consultantId");
        ObjectId consultantId = objectId(consultantText, "CONSULTANT_NOT_EXIST");
        String type = required(request, "typeOfConsult").toLowerCase(Locale.ROOT);
        if (!List.of("chat", "audio", "video").contains(type)) throw new IllegalArgumentException("INVALID_CONSULT_TYPE");
        for (String field : List.of("firstName", "gender", "dateOfBirth", "birthTime", "birthPlace", "applyCouponCodeId")) {
            required(request, field);
        }

        Document consultant = mongo.getCollection(Collections.CONSULTANTS).find(
                new Document("_id", consultantId).append("status", true).append("isDeleted", new Document("$ne", true))
                        .append("isAdminVerify", true)).first();
        if (consultant == null) throw new IllegalArgumentException("CONSULTANT_NOT_EXIST");
        if (mongo.getCollection(Collections.BROADCASTS).find(
                new Document("consultantId", consultantId).append("status", 1)).first() != null) {
            throw new IllegalStateException("CONSULTANT_IN_LIVE");
        }
        if (mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).find(
                new Document("userId", userId).append("isConsultantCompleted",
                        new Document("$nin", List.of("complete", "notcomplete", "cancel")))).first() != null) {
            throw new RequestInProgressException();
        }

        ObjectId couponId = objectId(request.get("applyCouponCodeId"), "COUPON_NOT_EXIST");
        Document coupon = mongo.getCollection(Collections.APPLIED_COUPONS)
                .find(new Document("_id", couponId)).first();
        if (coupon == null) throw new IllegalArgumentException("COUPON_NOT_EXIST");
        double price = number(coupon.get("consultantFinalCallChargeCoinMin"));
        boolean free = bool(coupon.get("isFirstChatFreeWithConsultant"));
        if (price <= 0 && !free) throw new IllegalStateException("MINUTE_LESSTHAN");

        Document wallet = mongo.getCollection(Collections.WALLETS).find(new Document("userId", userId)).first();
        double coins = wallet == null ? 0 : decryptCoins(wallet.get("coins"));
        if (coins <= 0 && !free) throw new IllegalStateException("MINUTE_LESSTHAN");
        double totalMinutes = price <= 0 ? 0 : round(coins / price, 2);
        if (free) totalMinutes += numberOr(request.get("freeTrialMinutes"), 5d);
        if (totalMinutes < 5) throw new IllegalStateException("MINUTE_LESSTHAN");

        String minuteBucket = Instant.now().toString().substring(0, 16);
        ObjectId deterministicId = deterministicObjectId(userId + "|" + consultantId + "|" + type + "|" + minuteBucket);
        Date now = new Date();
        Document form = new Document(new LinkedHashMap<>(request));
        for (String field : List.of("consultantChargeCoinMin", "membershipDiscountAmount",
                "couponDiscountAmount", "consultantFinalCallChargeCoinMin")) form.remove(field);
        form.put("_id", deterministicId);
        form.put("consultantId", consultantId);
        form.put("userId", userId);
        form.put("typeOfConsult", type);
        form.put("applyCouponCodeId", couponId);
        form.put("isNewUserForConsultant", mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .find(new Document("userId", userId).append("consultantId", consultantId)
                        .append("isConsultantCompleted", "complete")).first() == null);
        form.put("isNewUserForConsultantAndWithFreeTrail", bool(request.get("isWithFreeTrial")));
        form.put("graphologyUrl", new Document("url", request.get("graphologyUrl"))
                .append("type", request.get("graphologyType")).append("name", request.get("graphologyName")));
        form.put("orderId", bookings.nextOrderId());
        form.put("isConsultantBoostProfile", boostActive(consultantId, type));
        form.put("membershipDiscountAmount", coupon.get("membershipDiscountAmount"));
        form.put("totalCallMinutes", round(totalMinutes, 2));
        form.put("totalCallSeconds", Math.round(totalMinutes * 60));
        form.put("planType", bool(coupon.get("isUserHaveActiveMembership"))
                ? text(coupon.get("membershipPlan")) : "OTHERS");
        form.put("isUserHaveActiveMembership", bool(coupon.get("isUserHaveActiveMembership")));
        form.put("isConsultantCompleted", "waiting");
        form.put("createdAt", now);
        form.put("updatedAt", now);
        // Node spreads the applied-coupon snapshot last; preserve those audit fields.
        coupon.forEach((key, value) -> { if (!"_id".equals(key)) form.put(key, value); });

        try {
            mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).updateOne(
                    new Document("_id", deterministicId), new Document("$setOnInsert", form),
                    new UpdateOptions().upsert(true));
        } catch (MongoWriteException duplicate) {
            // A same-minute retry races with the first insert; return the single canonical record.
        }
        return mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .find(new Document("_id", deterministicId)).first();
    }

    /** Actor-scoped port of verify/wallet/balance; never accepts a caller-supplied user id. */
    public Document verifyWallet(Document user, String requestId) {
        ObjectId userId = actorId(user);
        ObjectId id = objectId(requestId, "RECORD_NOT_FOUND");
        Document filter = new Document("_id", id).append("userId", userId);
        Document form = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).find(filter).first();
        if (form == null) throw new IllegalArgumentException("RECORD_NOT_FOUND");
        if (bool(form.get("isNewUserForConsultantAndWithFreeTrail"))) return form;

        Document wallet = mongo.getCollection(Collections.WALLETS).find(new Document("userId", userId)).first();
        double coins = wallet == null ? 0 : decryptCoins(wallet.get("coins"));
        double price = number(form.get("consultantFinalCallChargeCoinMin"));
        if (coins <= 0 || price <= 0) throw new IllegalStateException("MINUTE_LESSTHAN");
        double minutes = round(coins / price, 2);
        if (minutes < 5) throw new IllegalStateException("MINUTE_LESSTHAN");
        return mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).findOneAndUpdate(filter,
                new Document("$set", new Document("totalCallMinutes", minutes)
                        .append("totalCallSeconds", Math.round(minutes * 60)).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    private boolean boostActive(ObjectId consultantId, String type) {
        String field = "chat".equals(type) ? "chatActive" : "audio".equals(type) ? "callActive" : "videoActive";
        return mongo.getCollection(Collections.CONSULTANT_BOOSTS)
                .find(new Document("consultantId", consultantId).append(field, true))
                .sort(new Document("createdAt", -1)).first() != null;
    }
    private Document flags(String type) {
        return new Document("callActive", "CALL".equals(type)).append("chatActive", "CHAT".equals(type))
                .append("videoActive", "VIDEO".equals(type));
    }
    private double decryptCoins(Object cipher) {
        if (cipher == null || text(cipher).isBlank()) return 0;
        try { return Double.parseDouble(crypto.decrypt(text(cipher)).trim()); }
        catch (RuntimeException invalid) { throw new IllegalStateException("INVALID_WALLET_BALANCE"); }
    }
    private ObjectId deterministicObjectId(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
            return new ObjectId(hex.toString());
        } catch (Exception impossible) { throw new IllegalStateException("IDEMPOTENCY_KEY_FAILURE", impossible); }
    }
    private ObjectId actorId(Document actor) {
        return objectId(actor == null ? null : actor.get("_id"), "USER_NOT_FOUND");
    }
    private ObjectId objectId(Object value, String error) {
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(text(value))) return new ObjectId(text(value));
        throw new IllegalArgumentException(error);
    }
    private String required(Map<String, Object> input, String key) {
        String value = text(input == null ? null : input.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (RuntimeException ignored) { return 0; }
    }
    private double numberOr(Object value, double fallback) {
        double parsed = number(value); return parsed <= 0 ? fallback : parsed;
    }
    private double round(double value, int scale) {
        double factor = Math.pow(10, scale); return Math.round(value * factor) / factor;
    }

    public static final class RequestInProgressException extends RuntimeException {
        public RequestInProgressException() { super("REQUEST_INPROGRESS"); }
    }
}
