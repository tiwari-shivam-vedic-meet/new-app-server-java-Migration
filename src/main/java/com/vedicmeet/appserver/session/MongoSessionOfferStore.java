package com.vedicmeet.appserver.session;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mongo port of getActiveOffer/coupon validation and single-use consumption for booking. */
@Repository
public class MongoSessionOfferStore implements SessionOfferStore {

    private final MongoTemplate mongo;

    public MongoSessionOfferStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Selection select(Document user, Document consultant, String requestedMode, String couponCode) {
        Object userId = user.get("_id");
        Selection coupon = activeCoupon(userId, consultant.get("_id"), couponCode);
        if (coupon != null) return coupon;

        Document activeState = mongo.getCollection(Collections.USER_OFFER_STATES)
                .find(new Document("userId", userId).append("isActive", true)
                        .append("isExhausted", new Document("$ne", true)))
                .sort(new Document("lastUsedAt", -1)).first();
        if (activeState != null) {
            Selection selected = selectionFromState(userId, activeState);
            if (selected != null && ruleEligible(selected.rule(), user, consultationCount(user))) {
                return selected;
            }
        }

        long count = consultationCount(user);
        Set<String> segments = null;
        List<Selection> eligible = new ArrayList<>();
        FindIterable<Document> rules = mongo.getCollection(Collections.OFFER_RULES)
                .find(new Document("isActive", true));
        for (Document rule : rules) {
            if (!ruleEligible(rule, user, count)) continue;
            List<String> needed = strings(rule.get("userSegments"));
            if (!needed.isEmpty()) {
                if (segments == null) segments = userSegments(user, count);
                if (needed.stream().noneMatch(segments::contains)) continue;
            }
            for (Object variantId : values(rule.get("variants"))) {
                Document variant = findById(Collections.OFFER_VARIANTS, variantId);
                if (variant != null && Boolean.TRUE.equals(variant.get("isActive"))) {
                    eligible.add(new Selection(Kind.SYSTEM_OFFER, userId, null,
                            rule.get("_id"), variant.get("_id"), null, rule, variant));
                }
            }
        }
        return eligible.stream().min(Comparator
                .comparingInt((Selection s) -> integer(doc(s.rule().get("display")).get("priority")))
                .thenComparingDouble(s -> comparablePrice(s.variant()))).orElse(null);
    }

    @Override
    public void consume(Selection selection) {
        if (selection == null) return;
        if (selection.kind() == Kind.MANUAL_COUPON) consumeCoupon(selection);
        else consumeSystemOffer(selection);
    }

    private Selection activeCoupon(Object userId, Object consultantId, String couponCode) {
        Document filter = new Document("userId", userId).append("isActive", true)
                .append("isExhausted", new Document("$ne", true));
        if (couponCode != null && !couponCode.isBlank()) {
            filter.append("couponCode", couponCode.trim().toUpperCase());
        }
        Document state = mongo.getCollection(Collections.USER_COUPON_STATES).find(filter)
                .sort(new Document("lastUsedAt", -1)).first();
        if (state == null) return null;
        Document coupon = state.get("couponId") == null
                ? mongo.getCollection(Collections.V2_COUPONS)
                    .find(new Document("code", state.getString("couponCode"))).first()
                : findById(Collections.V2_COUPONS, state.get("couponId"));
        if (!validCoupon(coupon, state, consultantId)) return null;
        return new Selection(Kind.MANUAL_COUPON, userId, state.get("_id"), null,
                null, coupon, null, null);
    }

    private boolean validCoupon(Document coupon, Document state, Object consultantId) {
        if (coupon == null || !Boolean.TRUE.equals(coupon.get("isActive"))) return false;
        Date now = new Date();
        if (coupon.getDate("validFrom") != null && now.before(coupon.getDate("validFrom"))) return false;
        if (coupon.getDate("validTo") != null && now.after(coupon.getDate("validTo"))) return false;
        if ("INFLUENCER".equals(coupon.getString("type"))) return false;
        if (!"CONSULTATION".equals(coupon.getString("appliesOn"))) return false;
        if ("CONSULTANT".equals(coupon.getString("type")) && coupon.get("consultantId") != null
                && !text(coupon.get("consultantId")).equals(text(consultantId))) return false;
        Number limit = coupon.get("usageLimit", Number.class);
        return limit == null || integer(state.get("timesUsed")) < limit.intValue();
    }

    private Selection selectionFromState(Object userId, Document state) {
        Document rule = findById(Collections.OFFER_RULES, state.get("offerRuleId"));
        if (rule == null) return null;
        Object variantId = state.get("variantId");
        Document variant = variantId == null ? firstActiveVariant(rule)
                : findById(Collections.OFFER_VARIANTS, variantId);
        if (variant == null || !Boolean.TRUE.equals(variant.get("isActive"))) return null;
        return new Selection(Kind.SYSTEM_OFFER, userId, state.get("_id"), rule.get("_id"),
                variant.get("_id"), null, rule, variant);
    }

    private boolean ruleEligible(Document rule, Document user, long consultationCount) {
        if (rule == null || !Boolean.TRUE.equals(rule.get("isActive"))) return false;
        Document trigger = doc(rule.get("trigger"));
        double min = number(trigger.get("min"));
        double max = trigger.get("max") == null ? Double.POSITIVE_INFINITY : number(trigger.get("max"));
        if (consultationCount < min || consultationCount > max) return false;
        Document validity = doc(rule.get("validity"));
        Date now = new Date();
        Date start = validity.getDate("startAt");
        Date end = validity.getDate("endAt");
        if (start != null && now.before(start)) return false;
        if (end != null && now.after(end)) return false;
        List<?> targetUsers = values(rule.get("targetUsers"));
        return targetUsers.isEmpty() || targetUsers.stream()
                .anyMatch(value -> text(value).equals(text(user.get("_id"))));
    }

    private void consumeCoupon(Selection selection) {
        Document coupon = selection.coupon();
        Number usageLimit = coupon.get("usageLimit", Number.class);
        Document filter = new Document("_id", selection.stateId()).append("userId", selection.userId())
                .append("isActive", true).append("isExhausted", new Document("$ne", true));
        if (usageLimit != null) {
            filter.append("$or", List.of(new Document("timesUsed", new Document("$lt", usageLimit)),
                    new Document("timesUsed", new Document("$exists", false))));
        }
        Document updated = mongo.getCollection(Collections.USER_COUPON_STATES).findOneAndUpdate(
                filter, new Document("$inc", new Document("timesUsed", 1))
                        .append("$set", new Document("lastUsedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("Coupon has already been fully consumed.");
        if (usageLimit != null && integer(updated.get("timesUsed")) >= usageLimit.intValue()) {
            mongo.getCollection(Collections.USER_COUPON_STATES).updateOne(
                    new Document("_id", updated.get("_id")),
                    new Document("$set", new Document("isExhausted", true).append("isActive", false)));
        }
    }

    private void consumeSystemOffer(Selection selection) {
        Document currentRule = findById(Collections.OFFER_RULES, selection.offerRuleId());
        if (currentRule == null || !Boolean.TRUE.equals(currentRule.get("isActive"))) {
            throw new IllegalStateException("Offer is no longer active or has expired.");
        }
        int maxUse = Math.max(1, integer(doc(currentRule.get("frequency")).get("maxUseCount")));
        Document state = mongo.getCollection(Collections.USER_OFFER_STATES).find(
                new Document("userId", selection.userId()).append("offerRuleId", selection.offerRuleId())).first();
        if (state == null) {
            Document created = new Document("userId", selection.userId())
                    .append("offerRuleId", selection.offerRuleId()).append("variantId", selection.variantId())
                    .append("timesShown", 0).append("timesUsed", 0).append("isExhausted", false)
                    .append("isActive", true).append("createdAt", new Date()).append("updatedAt", new Date());
            mongo.getCollection(Collections.USER_OFFER_STATES).insertOne(created);
            state = created;
        }
        if (Boolean.TRUE.equals(state.get("isExhausted")) || integer(state.get("timesUsed")) >= maxUse) {
            throw new IllegalStateException("Offer has already been fully consumed.");
        }
        Document updated = mongo.getCollection(Collections.USER_OFFER_STATES).findOneAndUpdate(
                new Document("_id", state.get("_id")).append("isExhausted", new Document("$ne", true))
                        .append("timesUsed", new Document("$lt", maxUse)),
                new Document("$inc", new Document("timesUsed", 1))
                        .append("$set", new Document("lastUsedAt", new Date()).append("isActive", false)
                                .append("variantId", selection.variantId()).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("Offer has already been fully consumed.");
        if (integer(updated.get("timesUsed")) >= maxUse) {
            mongo.getCollection(Collections.USER_OFFER_STATES).updateOne(
                    new Document("_id", updated.get("_id")),
                    new Document("$set", new Document("isExhausted", true).append("isActive", false)));
        }
    }

    private long consultationCount(Document user) {
        String phone = text(doc(user.get("details")).get("phone"));
        if (phone.isBlank()) return 0;
        return mongo.getCollection(Collections.WAITLISTS).countDocuments(
                new Document("request_form.phoneNumber", phone).append("status", "completed"));
    }

    private Set<String> userSegments(Document user, long totalConsultations) {
        Set<String> out = new HashSet<>();
        Date now = new Date();
        Date today = Date.from(now.toInstant().truncatedTo(ChronoUnit.DAYS));
        String phone = text(doc(user.get("details")).get("phone"));
        double wallet = number(user.get("wallet"));

        Document recharge = mongo.getCollection(Collections.WALLET_TRANSACTIONS)
                .find(new Document("userId", user.get("_id")).append("transactionFor", "topup")
                        .append("userType", "user").append("transactionType", 0))
                .sort(new Document("createdAt", -1)).first();
        long todayCount = completedSince(phone, today);
        if (recharge != null && recharge.getDate("createdAt") != null) {
            Date at = recharge.getDate("createdAt");
            double amount = recharge.get("coins") == null ? number(recharge.get("value")) : number(recharge.get("coins"));
            boolean todayRecharge = !at.before(today) && !at.after(now);
            if (todayRecharge && todayCount == 0 && totalConsultations == 0) out.add("RECHARGE_NO_CONSULT_SAME_DAY");
            if (totalConsultations == 0) out.add("RECHARGE_NEVER_CONSULTED");
            if (now.getTime() - at.getTime() > 72L * 60 * 60 * 1000 && amount > 0 && wallet / amount * 100 > 50) {
                out.add("RECHARGE_50PCT_REMAINING_72H");
                out.add("RECHARGE_50PCT_REMAINING_AFTER_72H");
            }
            if (todayRecharge && amount > 100 && todayCount == 0) out.add("RECHARGE_100RS_NO_CONSULT_SAME_DAY");
        }
        if (wallet > 200) out.add("WALLET_200_PLUS");
        if (totalConsultations > 0) {
            if (completedSince(phone, Date.from(Instant.now().minus(7, ChronoUnit.DAYS))) == 0) out.add("NO_CONSULT_7D");
            if (completedSince(phone, Date.from(Instant.now().minus(14, ChronoUnit.DAYS))) == 0) out.add("NO_CONSULT_14D");
            if (completedSince(phone, Date.from(Instant.now().minus(21, ChronoUnit.DAYS))) == 0) out.add("NO_CONSULT_21D");
            if (completedSince(phone, Date.from(Instant.now().minus(30, ChronoUnit.DAYS))) == 0) out.add("NO_CONSULT_30D");
        }
        long failed = mongo.getCollection(Collections.WAITLISTS).countDocuments(
                new Document("user_id", text(user.get("_id")))
                        .append("status", new Document("$in", List.of("missed", "cancelled", "waiting")))
                        .append("createdAt", new Document("$gte", Date.from(Instant.now().minus(1, ChronoUnit.DAYS)))));
        if (failed >= 2) out.add("MULTIPLE_FAILED_24H");
        addAgeSegments(user, out, now);
        return out;
    }

    private long completedSince(String phone, Date since) {
        if (phone.isBlank()) return 0;
        return mongo.getCollection(Collections.WAITLISTS).countDocuments(
                new Document("request_form.phoneNumber", phone).append("status", "completed")
                        .append("createdAt", new Document("$gte", since)));
    }

    private void addAgeSegments(Document user, Set<String> out, Date now) {
        long lastOffline = 0;
        for (Object value : values(user.get("logs"))) {
            Document log = value instanceof Document d ? d : new Document();
            if (!"offline".equals(log.getString("status"))) continue;
            Object timestamp = log.get("timestamp");
            long time = timestamp instanceof Date date ? date.getTime()
                    : timestamp instanceof Number n ? n.longValue() : 0;
            lastOffline = Math.max(lastOffline, time);
        }
        if (lastOffline > 0) {
            long days = (now.getTime() - lastOffline) / (24L * 60 * 60 * 1000);
            if (days >= 30) out.add("INACTIVE_30D");
            else if (days >= 21) out.add("INACTIVE_21D");
            else if (days >= 14) out.add("INACTIVE_14D");
            else if (days >= 7) out.add("INACTIVE_7D");
        }
        Date created = user.getDate("createdAt");
        if (created != null) {
            long age = now.getTime() - created.getTime();
            if (age <= 24L * 60 * 60 * 1000) out.add("REGISTRATION_24H");
            if (age <= 48L * 60 * 60 * 1000) out.add("REGISTRATION_48H");
        }
    }

    private Document firstActiveVariant(Document rule) {
        for (Object id : values(rule.get("variants"))) {
            Document variant = findById(Collections.OFFER_VARIANTS, id);
            if (variant != null && Boolean.TRUE.equals(variant.get("isActive"))) return variant;
        }
        return null;
    }

    private Document findById(String collection, Object raw) {
        if (raw == null) return null;
        return mongo.getCollection(collection).find(new Document("_id", id(raw))).first();
    }

    private double comparablePrice(Document variant) {
        String type = variant.getString("type");
        double price = number(variant.get("price"));
        double duration = number(variant.get("duration"));
        if ("PERCENT".equals(type)) return 0;
        if ("FIXED".equals(type)) return duration > 0 ? price / (duration / 60.0) : 999999;
        return price;
    }

    private Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String value = text(raw);
        return ObjectId.isValid(value) ? new ObjectId(value) : raw;
    }
    private static List<?> values(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static List<String> strings(Object value) { return values(value).stream().map(MongoSessionOfferStore::text).toList(); }
    private static Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int integer(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(text(value)); } catch (NumberFormatException ignored) { return 0; }
    }
    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (NumberFormatException ignored) { return 0; }
    }
}
