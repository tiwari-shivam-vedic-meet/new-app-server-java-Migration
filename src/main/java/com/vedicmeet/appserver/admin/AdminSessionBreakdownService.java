package com.vedicmeet.appserver.admin;

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
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminSessionBreakdownService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminSessionBreakdownService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    // ── GET /admin/v1/session/:id/breakdown ──────────────────────────────
    public Map<String, Object> breakdown(String id) {
        // FAITHFUL: session-breakdown.js:118 — invalid ObjectId -> {code:400,'Invalid session ID'}
        // (the in-body 400 collapses to executeData's 500; the message is preserved).
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("Invalid session ID");

        Document session = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", new ObjectId(id))).first();
        // FAITHFUL: session-breakdown.js:123 — not found -> {code:404,'Session not found'}.
        if (session == null) throw new IllegalStateException("Session not found");

        Document master = mongo.getCollection(Collections.MASTERS)
                .find(new Document()).projection(new Document("payment", 1)).first();
        Object commission = master == null ? null : nested(master, "payment", "commission");
        double adminCommission = truthy(commission) ? number(commission) : 40; // masterData?.payment?.commission || 40

        Document user = mongo.getCollection(Collections.USERS)
                .find(new Document("_id", session.get("user_id")))
                .projection(new Document("name", 1).append("details.phone", 1)).first();
        Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", session.get("consultant_id")))
                .projection(new Document("accountName", 1).append("price.default", 1)).first();

        Map<String, Object> data = new LinkedHashMap<>(buildBreakdown(session, adminCommission));

        Map<String, Object> userObj = new LinkedHashMap<>();
        userObj.put("id", session.get("user_id"));
        userObj.put("name", user != null && truthy(user.get("name")) ? user.get("name") : "\u2014");
        Object phone = user == null ? null : nested(user, "details", "phone");
        userObj.put("phone", truthy(phone) ? phone : "\u2014");
        data.put("user", userObj);

        Map<String, Object> consultantObj = new LinkedHashMap<>();
        consultantObj.put("id", session.get("consultant_id"));
        consultantObj.put("name", consultant != null && truthy(consultant.get("accountName"))
                ? consultant.get("accountName") : "\u2014");
        Object price = consultant == null ? null : nested(consultant, "price", "default");
        consultantObj.put("price", truthy(price) ? price : 0);
        data.put("consultant", consultantObj);

        return data;
    }

    // ── GET /admin/v1/session/analytics/coupon-offer ─────────────────────
    public Map<String, Object> couponOfferAnalytics(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        Document match = new Document("status", "completed");
        String consultantId = q.get("consultantId");
        // FAITHFUL(node-quirk): session-breakdown.js:200 assigns the raw string; native aggregate does NOT cast to ObjectId.
        if (truthy(consultantId) && ObjectId.isValid(consultantId)) {
            match.append("consultant_id", consultantId);
        }
        appendDateRange(match, q);

        List<Document> summary = mongo.getCollection(Collections.WAITLISTS)
                .aggregate(summaryPipeline(match)).into(new ArrayList<>());
        Document matchCoupon = new Document(match).append("coupon", new Document("$ne", null));
        int limit = qNumber(q, "limit", 10);
        int page = qNumber(q, "page", 0);
        List<Document> perCoupon = mongo.getCollection(Collections.WAITLISTS)
                .aggregate(perCouponPipeline(matchCoupon, page, limit)).into(new ArrayList<>());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary.isEmpty() ? new LinkedHashMap<>() : summary.get(0)); // summary[0] || {}
        data.put("perCoupon", perCoupon);
        return data;
    }

    // ── GET /admin/v1/session/analytics/consultant-coupon ────────────────
    public Map<String, Object> consultantCouponAnalytics(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        String consultantId = q.get("consultantId");
        // FAITHFUL: session-breakdown.js:262 — consultantId required (in-body 400 collapses to 500; message preserved).
        if (!truthy(consultantId) || !ObjectId.isValid(consultantId)) {
            throw new IllegalArgumentException("consultantId is required");
        }
        // FAITHFUL(node-quirk): consultant_id kept as the raw string; native aggregate does NOT cast to ObjectId.
        Document match = new Document("status", "completed").append("consultant_id", consultantId);
        appendDateRange(match, q);

        List<Document> overall = mongo.getCollection(Collections.WAITLISTS)
                .aggregate(overallPipeline(match)).into(new ArrayList<>());
        Document matchCoupon = new Document(match).append("coupon", new Document("$ne", null));
        List<Document> couponBreakdown = mongo.getCollection(Collections.WAITLISTS)
                .aggregate(couponBreakdownPipeline(matchCoupon)).into(new ArrayList<>());
        List<Document> recent = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document(match).append("coupon", new Document("$ne", null)))
                .projection(new Document("user_id", 1).append("coupon", 1).append("onCompletion", 1)
                        .append("timeLap", 1).append("session_info", 1))
                .sort(new Document("timeLap.endTime", -1))
                .limit(10)
                .into(new ArrayList<>());

        List<Map<String, Object>> recentSessions = new ArrayList<>();
        for (Document s : recent) {
            Document oc = doc(s, "onCompletion");
            Document tl = doc(s, "timeLap");
            Document si = doc(s, "session_info");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("_id"));
            m.put("coupon", s.get("coupon"));
            m.put("earned", truthy(oc.get("consultantAmount")) ? oc.get("consultantAmount") : 0);
            m.put("revenue", truthy(oc.get("baseAmount")) ? oc.get("baseAmount") : 0);
            m.put("durationMins", round1(numberOr(oc.get("callDurationInSeconds"), 0) / 60.0));
            // FAITHFUL(node-quirk): session-breakdown.js:322-323 endedAt/mode have no `|| fallback`, so a
            // missing value is JS `undefined` (dropped by res.json); Java emits null instead.
            m.put("endedAt", tl.get("endTime"));
            m.put("mode", si.get("mode"));
            recentSessions.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overall", overall.isEmpty() ? new LinkedHashMap<>() : overall.get(0)); // overall[0] || {}
        data.put("couponBreakdown", couponBreakdown);
        data.put("recentSessions", recentSessions);
        return data;
    }

    // ── buildBreakdown (faithful port of session-breakdown.js:20-106) ────
    Map<String, Object> buildBreakdown(Document session, double adminCommission) {
        Document oc = doc(session, "onCompletion");
        Document coupon = session.get("coupon") instanceof Document c ? c : null; // session.coupon || null
        Document sessionInfo = doc(session, "session_info");

        double totalSeconds = numberOr(oc.get("callDurationInSeconds"), 0);
        double totalMinutes = round2(totalSeconds / 60.0);
        double extraSeconds = numberOr(oc.get("extraDuration"), 0);
        double extraMinutes = round2(extraSeconds / 60.0);
        double baseSeconds = Math.max(0, totalSeconds - extraSeconds);
        double baseMinutes = round2(baseSeconds / 60.0);

        double baseRate = numberOr(sessionInfo.get("price"), 0);
        double baseAmount = numberOr(oc.get("baseAmount"), 0);
        double platformAmount = numberOr(oc.get("platformAmount"), 0);
        double consultantAmount = numberOr(oc.get("consultantAmount"), 0);

        // coupon?.couponDiscount ? baseRate - (baseRate * discount / 100) : null
        Double offerRate = (coupon != null && truthy(coupon.get("couponDiscount")))
                ? baseRate - (baseRate * number(coupon.get("couponDiscount")) / 100.0)
                : null;
        Double offerAmount = offerRate != null ? round2(offerRate * baseMinutes) : null;

        double extAmount = numberOr(oc.get("extraDurationAmount"), 0);
        double effectivePlatformRate = baseAmount > 0
                ? round2((platformAmount / baseAmount) * 100.0)
                : adminCommission; // Number(adminCommission || 0) — already coalesced by the caller

        Map<String, Object> couponInfo = null;
        if (coupon != null) {
            couponInfo = new LinkedHashMap<>();
            couponInfo.put("code", firstTruthy(coupon.get("couponCode"), coupon.get("code"), "\u2014"));
            couponInfo.put("type", truthy(coupon.get("type")) ? coupon.get("type") : "unknown");
            couponInfo.put("discount", truthy(coupon.get("couponDiscount")) ? coupon.get("couponDiscount") : null);
            couponInfo.put("title", truthy(coupon.get("title")) ? coupon.get("title") : null);
            couponInfo.put("appliedOn", truthy(coupon.get("appliesOn")) ? coupon.get("appliesOn")
                    : ("INFLUENCER".equals(value(coupon.get("type"))) ? "RECHARGE" : "CONSULTATION"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.get("_id"));
        result.put("status", session.get("status"));
        result.put("callType", truthy(sessionInfo.get("mode")) ? sessionInfo.get("mode") : "unknown");
        Document timeLap = doc(session, "timeLap");
        result.put("startedAt", truthy(timeLap.get("startTime")) ? timeLap.get("startTime") : null);
        result.put("endedAt", truthy(timeLap.get("endTime")) ? timeLap.get("endTime") : null);

        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("baseRate", baseRate);
        pricing.put("offerRate", offerRate); // null if no offer
        pricing.put("hasOffer", offerRate != null && offerRate != baseRate);
        result.put("pricing", pricing);

        Map<String, Object> duration = new LinkedHashMap<>();
        duration.put("total", secMin(totalSeconds, totalMinutes));
        duration.put("offer", secMin(baseSeconds, baseMinutes));
        duration.put("extension", secMin(extraSeconds, extraMinutes));
        result.put("duration", duration);

        Map<String, Object> amounts = new LinkedHashMap<>();
        amounts.put("offerAmount", offerAmount != null ? offerAmount : round2(baseRate * baseMinutes)); // offerAmount ?? ...
        amounts.put("extensionAmount", extAmount);
        amounts.put("totalAmount", baseAmount);
        amounts.put("platformFee", platformAmount);
        amounts.put("consultantEarned", consultantAmount);
        amounts.put("platformFeeRate", effectivePlatformRate);
        result.put("amounts", amounts);

        result.put("coupon", couponInfo);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("baseAmount", baseAmount);
        raw.put("platformAmount", platformAmount);
        raw.put("consultantAmount", consultantAmount);
        raw.put("extraDuration", extraSeconds);
        raw.put("extraDurationAmount", extAmount);
        raw.put("callDurationInSeconds", totalSeconds);
        result.put("raw", raw);

        return result;
    }

    // ── Aggregation pipeline builders (package-private for shape tests) ───
    List<Document> summaryPipeline(Document match) {
        return List.of(
                new Document("$match", match),
                new Document("$group", new Document("_id", null)
                        .append("totalSessions", sum(1))
                        .append("sessionsWithCoupon", sum(cond(ne("$coupon", null), 1, 0)))
                        .append("sessionsWithOffer", sum(cond(eq("$coupon.type", "OFFERS"), 1, 0)))
                        .append("totalRevenue", sum(ifNull("$onCompletion.baseAmount", 0)))
                        .append("totalConsultantEarned", sum(ifNull("$onCompletion.consultantAmount", 0)))
                        .append("totalPlatformFee", sum(ifNull("$onCompletion.platformAmount", 0)))
                        .append("totalExtensionAmount", sum(ifNull("$onCompletion.extraDurationAmount", 0)))
                        .append("avgDurationSecs", new Document("$avg", ifNull("$onCompletion.callDurationInSeconds", 0)))));
    }

    List<Document> perCouponPipeline(Document matchCoupon, int page, int limit) {
        return List.of(
                new Document("$match", matchCoupon),
                new Document("$group", new Document("_id", "$coupon.couponCode")
                        .append("type", first("$coupon.type"))
                        .append("title", first("$coupon.title"))
                        .append("discount", first("$coupon.couponDiscount"))
                        .append("usageCount", sum(1))
                        .append("totalRevenue", sum(ifNull("$onCompletion.baseAmount", 0)))
                        .append("consultantEarned", sum(ifNull("$onCompletion.consultantAmount", 0)))
                        .append("avgDurSecs", new Document("$avg", ifNull("$onCompletion.callDurationInSeconds", 0)))),
                new Document("$sort", new Document("usageCount", -1)),
                new Document("$skip", page * limit),
                new Document("$limit", limit));
    }

    List<Document> overallPipeline(Document match) {
        return List.of(
                new Document("$match", match),
                new Document("$group", new Document("_id", null)
                        .append("totalSessions", sum(1))
                        .append("withCoupon", sum(cond(ne("$coupon", null), 1, 0)))
                        .append("withOffer", sum(cond(eq("$coupon.type", "OFFERS"), 1, 0)))
                        .append("totalEarned", sum(ifNull("$onCompletion.consultantAmount", 0)))
                        .append("totalRevenue", sum(ifNull("$onCompletion.baseAmount", 0)))
                        .append("totalExtensionRevenue", sum(ifNull("$onCompletion.extraDurationAmount", 0)))
                        .append("avgDurMins", new Document("$avg", divide(ifNull("$onCompletion.callDurationInSeconds", 0), 60)))));
    }

    List<Document> couponBreakdownPipeline(Document matchCoupon) {
        return List.of(
                new Document("$match", matchCoupon),
                new Document("$group", new Document("_id", "$coupon.couponCode")
                        .append("type", first("$coupon.type"))
                        .append("title", first("$coupon.title"))
                        .append("discount", first("$coupon.couponDiscount"))
                        .append("usageCount", sum(1))
                        .append("earned", sum(ifNull("$onCompletion.consultantAmount", 0)))
                        .append("revenue", sum(ifNull("$onCompletion.baseAmount", 0)))
                        .append("avgDurMins", new Document("$avg", divide(ifNull("$onCompletion.callDurationInSeconds", 0), 60)))),
                new Document("$sort", new Document("usageCount", -1)),
                new Document("$limit", 20));
    }

    private void appendDateRange(Document match, Map<String, String> q) {
        String from = q.get("from");
        String to = q.get("to");
        if (truthy(from) || truthy(to)) {
            Document range = new Document();
            if (truthy(from)) range.append("$gte", jsDate(from));
            if (truthy(to)) range.append("$lte", jsDate(to));
            match.append("timeLap.endTime", range);
        }
    }

    private static Map<String, Object> secMin(double seconds, double minutes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seconds", seconds);
        m.put("minutes", minutes);
        return m;
    }

    private static Document sum(Object expr) { return new Document("$sum", expr); }

    private static Document first(Object expr) { return new Document("$first", expr); }

    private static Document cond(Object ifExpr, Object thenVal, Object elseVal) {
        return new Document("$cond", Arrays.asList(ifExpr, thenVal, elseVal));
    }

    private static Document ne(Object a, Object b) { return new Document("$ne", Arrays.asList(a, b)); }

    private static Document eq(Object a, Object b) { return new Document("$eq", Arrays.asList(a, b)); }

    private static Document ifNull(Object a, Object b) { return new Document("$ifNull", Arrays.asList(a, b)); }

    private static Document divide(Object a, Object b) { return new Document("$divide", Arrays.asList(a, b)); }

    private static Document doc(Document parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Document d ? d : new Document();
    }

    private static Object nested(Document parent, String first, String second) {
        Object value = parent == null ? null : parent.get(first);
        return value instanceof Document d ? d.get(second) : null;
    }

    private static Object firstTruthy(Object a, Object b, Object fallback) {
        if (truthy(a)) return a;
        if (truthy(b)) return b;
        return fallback;
    }

    private static double numberOr(Object value, double fallback) {
        return truthy(value) ? number(value) : fallback;
    }

    private static int qNumber(Map<String, String> q, String key, int fallback) {
        if (q == null || !q.containsKey(key)) return fallback; // destructuring default applies only when undefined
        String v = q.get(key);
        if (v == null || v.isEmpty()) return v == null ? fallback : 0; // Number('') === 0
        try {
            return (int) Double.parseDouble(v.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
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

    private static double round1(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}