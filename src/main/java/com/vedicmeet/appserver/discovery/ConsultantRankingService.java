package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Faithful port of Node's consultant ranking helper
 * ({@code ConsultantService.topConsultant} + its 4 leaf aggregations,
 * utils/classes/consultant-service.js L3685–3883, checkConsIsNew L5635).
 *
 * Returns the tag string exactly as Node does: "top" | "second" | "third" | "new" | "".
 * This is a READ-only ranking helper — it moves no money. Like the already-ported
 * `banner` module, it is a faithful port that must still pass the contract-harness
 * diff on the TEST server before it is wired into a live `/v2` endpoint.
 *
 * Ranking thresholds are the exact Node _constant.js values (L180–197).
 *
 * ⚠ Two subtle Node behaviours preserved deliberately:
 *   1. The rating thresholds are `parseInt`-ed in Node's checkStatus (parseInt(4.2)=4,
 *      parseInt(4.8)=4, parseInt(4.5)=4) — so every tier effectively needs avgRating >= 4.
 *      Reproduced via {@link #parseInt(double)} (truncation toward zero).
 *   2. revenuePercent = revenue*100 / totalRevenueOfAllConsultant can be NaN/Infinity when
 *      the denominator is 0 (JS). `NaN >= x` is false in both JS and Java, so the
 *      comparison outcome is identical — kept as a plain double division.
 */
@Service
public class ConsultantRankingService {

    // --- Node _constant.js L180–197 (exact) ---
    private static final int TOP_LIVE = 14, TOP_ONLINE = 12, TOP_REVENUE = 12000, TOP_REV_PCT = 12;
    private static final double TOP_AVG_RATING = 4.2; private static final int TOP_TOTAL_RATING = 50;
    private static final int SEC_LIVE = 12, SEC_ONLINE = 10, SEC_REVENUE = 9000, SEC_REV_PCT = 9;
    private static final double SEC_AVG_RATING = 4.8; private static final int SEC_TOTAL_RATING = 100;
    private static final int THIRD_LIVE = 10, THIRD_ONLINE = 8, THIRD_REVENUE = 5000, THIRD_REV_PCT = 5;
    private static final double THIRD_AVG_RATING = 4.5; private static final int THIRD_TOTAL_RATING = 200;

    private final MongoTemplate mongo;

    public ConsultantRankingService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Score-less overload — Node's consultantDetails/consultantList call
     * {@code topConsultant(element._id)} with NO score, so {@code Number(undefined)} is NaN.
     * Passing NaN reproduces that: the {@code (totalRevenueGeneration + NaN)} comparison is
     * always false, so the revenue tiers can't match and it falls through to the "new" check.
     */
    public String topConsultant(String consultantId) {
        return topConsultant(consultantId, Double.NaN);
    }

    /** Port of topConsultant(consultantId, score) — returns "top"|"second"|"third"|"new"|"". */
    public String topConsultant(String consultantId, double score) {
        Ranking d = new Ranking();
        Document rev = consultantRevenueAndOnlineAvailability(consultantId);
        Document rating = consultantRating(consultantId);
        Document live = consultantLiveAvailability(consultantId);
        d.totalHours = rev.getDouble("totalHours");
        d.totalRevenueGeneration = num(rev.get("totalRevenueGeneration"));
        d.revenuePercent = rev.getDouble("revenuePercent");
        d.avgRating = num(rating.get("avgRating"));
        d.totalRating = num(rating.get("totalRating"));
        d.totalLiveHours = live.getDouble("totalLiveHours");

        String status = "";
        if (checkStatus(d, score, TOP_LIVE, TOP_ONLINE, TOP_REVENUE, TOP_REV_PCT, TOP_AVG_RATING, TOP_TOTAL_RATING)) {
            status = "top";
        } else if (checkStatus(d, score, SEC_LIVE, SEC_ONLINE, SEC_REVENUE, SEC_REV_PCT, SEC_AVG_RATING, SEC_TOTAL_RATING)) {
            status = "second";
        } else if (checkStatus(d, score, THIRD_LIVE, THIRD_ONLINE, THIRD_REVENUE, THIRD_REV_PCT, THIRD_AVG_RATING, THIRD_TOTAL_RATING)) {
            status = "third";
        }

        if (status.isEmpty()) {
            List<Document> isNewTag = checkConsIsNew(consultantId);
            if (!isNewTag.isEmpty() && Boolean.TRUE.equals(isNewTag.get(0).getBoolean("result"))) {
                status = "new";
            }
        }
        return status;
    }

    /** Node checkStatus(...) predicate — thresholds are parseInt-ed exactly as Node does. */
    private boolean checkStatus(Ranking data, double score, int liveAvail, int onlineAvail,
                                int totalRev, int revPercent, double avgRating, int totalRating) {
        return data.totalLiveHours >= parseInt(liveAvail)
                && data.totalHours >= parseInt(onlineAvail)
                && (data.totalRevenueGeneration + score) >= parseInt(totalRev)
                && data.revenuePercent >= parseInt(revPercent)
                && data.avgRating >= parseInt(avgRating)          // parseInt(4.2)=4, etc.
                && data.totalRating >= parseInt(totalRating);
    }

    /** consultant-service.js L3685 — $facet revenue + all-consultant revenue over consultantformrequests. */
    private Document consultantRevenueAndOnlineAvailability(String consultantId) {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("isConsultantCompleted", "complete")),
                new Document("$facet", new Document()
                        .append("consultantTotal", Arrays.asList(
                                new Document("$match", new Document("consultantId", new ObjectId(consultantId))),
                                new Document("$group", new Document("_id", null)
                                        .append("totalMinutes", new Document("$sum", "$totalUserUsedCallMinutes"))
                                        .append("totalAmountPayToConsultant", new Document("$sum", "$totalAmountPayToConsultant")))))
                        .append("total", Arrays.asList(
                                new Document("$group", new Document("_id", null)
                                        .append("totalAmountEarnAllConsultant", new Document("$sum", "$totalAmountPayToConsultant")))))));

        Document facet = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .aggregate(pipeline).first();

        List<Document> consultantTotal = facet == null ? new ArrayList<>() : facet.getList("consultantTotal", Document.class, new ArrayList<>());
        List<Document> total = facet == null ? new ArrayList<>() : facet.getList("total", Document.class, new ArrayList<>());

        double totalMinutes = consultantTotal.isEmpty() ? 0 : num(consultantTotal.get(0).get("totalMinutes"));
        double totalHours = consultantTotal.isEmpty() ? 0 : totalMinutes / 60.0;
        double totalRevenueGeneration = consultantTotal.isEmpty() ? 0 : num(consultantTotal.get(0).get("totalAmountPayToConsultant"));
        double totalRevenueOfAllConsultant = total.isEmpty() ? 0 : num(total.get(0).get("totalAmountEarnAllConsultant"));
        double revenuePercent = (totalRevenueGeneration * 100) / totalRevenueOfAllConsultant; // may be NaN/Inf like Node

        return new Document("totalHours", toFixed1(totalHours))
                .append("totalRevenueGeneration", totalRevenueGeneration)
                .append("revenuePercent", revenuePercent);
    }

    /** consultant-service.js L3749 — avg rating over review_and_ratings (status:true). */
    private Document consultantRating(String consultantId) {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("consultantId", new ObjectId(consultantId)).append("status", true)),
                new Document("$group", new Document("_id", null)
                        .append("avgRating", new Document("$avg", "$rating"))
                        .append("totalRatig", new Document("$sum", 1)))); // Node key is misspelled 'totalRatig'
        Document r = mongo.getCollection(Collections.REVIEW_AND_RATINGS).aggregate(pipeline).first();
        double avgRating = r == null ? 0 : num(r.get("avgRating"));
        double totalRating = r == null ? 0 : num(r.get("totalRatig"));
        return new Document("totalRating", totalRating).append("avgRating", avgRating);
    }

    /** consultant-service.js L3775 — total live minutes over broadcasts (status:2 = complete). */
    private Document consultantLiveAvailability(String consultantId) {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("consultantId", new ObjectId(consultantId)).append("status", 2)),
                new Document("$group", new Document("_id", null)
                        .append("totalMinutes", new Document("$sum", "$totalTimeSpentInLiveMinutes"))));
        Document r = mongo.getCollection(Collections.BROADCASTS).aggregate(pipeline).first();
        double totalMinutes = r == null ? 0 : num(r.get("totalMinutes"));
        double totalLiveHours = r == null ? 0 : toFixed1(totalMinutes / 60.0);
        return new Document("totalLiveHours", totalLiveHours);
    }

    /** consultant-service.js L5635 — consultant createdAt within the last 75 days => result:true. */
    private List<Document> checkConsIsNew(String consultantId) {
        Date seventyFiveDaysAgo = new Date(System.currentTimeMillis() - 75L * 24 * 60 * 60 * 1000);
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("_id", new ObjectId(consultantId))),
                new Document("$addFields", new Document("result", new Document("$cond", new Document()
                        .append("if", new Document("$gte", Arrays.asList("$createdAt", seventyFiveDaysAgo)))
                        .append("then", true)
                        .append("else", false)))),
                new Document("$project", new Document("_id", 1).append("createdAt", 1).append("result", 1)));
        return mongo.getCollection(Collections.CONSULTANTS).aggregate(pipeline).into(new ArrayList<>());
    }

    /** JS parseInt on an already-integer int (identity) — kept for 1:1 readability with Node. */
    private int parseInt(int v) { return v; }

    /** JS parseInt(double) truncates toward zero: parseInt(4.8) === 4. */
    private int parseInt(double v) { return (int) v; }

    /** JS (n).toFixed(1) then parseFloat — round half-up to 1 decimal place. */
    private double toFixed1(double v) { return Math.round(v * 10.0) / 10.0; }

    private double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0.0; }

    /** Working holder for the merged ranking inputs (Node spreads 3 objects into one). */
    private static final class Ranking {
        double totalHours;
        double totalRevenueGeneration;
        double revenuePercent;
        double avgRating;
        double totalRating;
        double totalLiveHours;
    }
}
