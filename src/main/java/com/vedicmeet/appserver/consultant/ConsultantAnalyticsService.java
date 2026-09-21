package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.Sorts;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only consultant performance reports from consultant/analytics.js. */
@Service
public class ConsultantAnalyticsService {

    private final MongoTemplate mongo;

    public ConsultantAnalyticsService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Map<String, Object> performanceReport(Document consultant) {
        Object consultantObjectId = consultant.get("_id");
        String consultantId = id(consultant);
        Date start30 = startOfDay(30);
        Date start7 = startOfDay(7);
        Date end = Date.from(LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1));

        long flags = mongo.getCollection(Collections.FLAG_LOGS).countDocuments(
                new Document("consultant_id", consultantObjectId).append("flag_type", "missed_call")
                        .append("flag_status", "resolved")
                        .append("createdAt", new Document("$gte", start30).append("$lte", end)));

        double onlineSeconds = 0;
        for (Document stat : mongo.getCollection(Collections.STATS).find(
                new Document("userType", "cons").append("userID", consultantObjectId)
                        .append("for", "app_online")
                        .append("updatedAt", new Document("$gte", start7).append("$lte", end)))) {
            onlineSeconds += number(stat.get("totalTime"));
        }

        // Node currently groups by userId (not user_id); preserve this result contract for diff parity.
        Document firstGroup = new Document("_id", "$userId")
                .append("hasFreeCall", conditionalSum(
                        new Document("$ne", java.util.Arrays.asList("$coupon", null))))
                .append("hasPaidCall", conditionalSum(
                        new Document("$eq", java.util.Arrays.asList("$coupon", null))));
        Document repeatCondition = new Document("$and", List.of(
                new Document("$gt", List.of("$hasFreeCall", 0)),
                new Document("$gt", List.of("$hasPaidCall", 0))));
        Document secondGroup = new Document("_id", null)
                .append("freeUsers", conditionalSum(new Document("$gt", List.of("$hasFreeCall", 0))))
                .append("repeatedUsers", conditionalSum(repeatCondition));
        List<Document> conversion = mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("consultant_id", consultantId)
                        .append("status", "completed")
                        .append("createdAt", new Document("$gte", start7).append("$lte", end))),
                new Document("$group", firstGroup),
                new Document("$group", secondGroup)
        )).into(new ArrayList<>());
        double conversionPercentage = 0;
        if (!conversion.isEmpty() && number(conversion.get(0).get("freeUsers")) > 0) {
            conversionPercentage = Math.round(number(conversion.get(0).get("repeatedUsers"))
                    / number(conversion.get(0).get("freeUsers")) * 100);
        }

        List<Document> ratings = mongo.getCollection(Collections.REVIEW_AND_RATINGS).aggregate(List.of(
                new Document("$match", new Document("consultantId", consultantObjectId)
                        .append("status", true).append("createdAt", new Document("$gte", start30))),
                new Document("$lookup", new Document("from", Collections.WAITLISTS)
                        .append("let", new Document("requestFormId", "$consultantRequestFormId"))
                        .append("pipeline", List.of(
                                new Document("$match", new Document("$expr",
                                        new Document("$eq", List.of("$_id", "$$requestFormId")))),
                                new Document("$project", new Document("isFreeCall",
                                        new Document("$ne", java.util.Arrays.asList("$coupon", null))))))
                        .append("as", "waitlistData")),
                new Document("$unwind", new Document("path", "$waitlistData")
                        .append("preserveNullAndEmptyArrays", true)),
                new Document("$group", new Document("_id", "$waitlistData.isFreeCall")
                        .append("averageRating", new Document("$avg", "$rating")))
        )).into(new ArrayList<>());

        double freeRating = 0;
        double paidRating = 0;
        for (Document rating : ratings) {
            if (Boolean.TRUE.equals(rating.get("_id"))) freeRating = number(rating.get("averageRating"));
            if (Boolean.FALSE.equals(rating.get("_id"))) paidRating = number(rating.get("averageRating"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("last30DaysMissedCall", flags);
        result.put("last7DaysOnlineTime", Math.round(onlineSeconds / 3600d) / 7d);
        result.put("last7DaysConversionPercentage", conversionPercentage);
        result.put("freeRatingsPercentage", freeRating);
        result.put("paidRatingsPercentage", paidRating);
        return result;
    }

    public Map<String, Object> couponOffer(Document consultant, String from, String to) {
        Document match = new Document("consultant_id", id(consultant)).append("status", "completed");
        Document dates = new Document();
        Date fromDate = parseDate(from);
        Date toDate = parseDate(to);
        if (fromDate != null) dates.append("$gte", fromDate);
        if (toDate != null) dates.append("$lte", toDate);
        if (!dates.isEmpty()) match.append("timeLap.endTime", dates);

        List<Document> overallResult = mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", match),
                new Document("$group", new Document("_id", null)
                        .append("totalSessions", new Document("$sum", 1))
                        .append("withCouponOrOffer", conditionalSum(new Document("$ne", java.util.Arrays.asList("$coupon", null))))
                        .append("withOffer", conditionalSum(new Document("$eq", List.of("$coupon.type", "OFFERS"))))
                        .append("totalEarned", sumIfNull("$onCompletion.consultantAmount"))
                        .append("totalRevenue", sumIfNull("$onCompletion.baseAmount"))
                        .append("totalExtensionRevenue", sumIfNull("$onCompletion.extraDurationAmount"))
                        .append("avgDurMins", new Document("$avg", new Document("$divide",
                                List.of(new Document("$ifNull", List.of("$onCompletion.callDurationInSeconds", 0)), 60)))))
        )).into(new ArrayList<>());

        Document couponMatch = new Document(match).append("coupon", new Document("$ne", null));
        List<Document> perCoupon = mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", couponMatch),
                new Document("$group", new Document("_id", "$coupon.couponCode")
                        .append("type", new Document("$first", "$coupon.type"))
                        .append("title", new Document("$first", "$coupon.title"))
                        .append("discount", new Document("$first", "$coupon.couponDiscount"))
                        .append("usageCount", new Document("$sum", 1))
                        .append("earned", sumIfNull("$onCompletion.consultantAmount"))
                        .append("revenue", sumIfNull("$onCompletion.baseAmount"))
                        .append("avgDurMins", new Document("$avg", new Document("$divide",
                                List.of(new Document("$ifNull", List.of("$onCompletion.callDurationInSeconds", 0)), 60))))),
                new Document("$sort", new Document("usageCount", -1)),
                new Document("$limit", 15)
        )).into(new ArrayList<>());

        List<Map<String, Object>> recent = new ArrayList<>();
        for (Document session : mongo.getCollection(Collections.WAITLISTS).find(couponMatch)
                .projection(new Document("coupon", 1).append("onCompletion", 1)
                        .append("timeLap", 1).append("session_info", 1))
                .sort(Sorts.descending("timeLap.endTime")).limit(5)) {
            Document completion = doc(session.get("onCompletion"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.get("_id"));
            item.put("coupon", session.get("coupon"));
            item.put("earned", number(completion.get("consultantAmount")));
            item.put("revenue", number(completion.get("baseAmount")));
            item.put("durationMins", Math.round(number(completion.get("callDurationInSeconds")) / 6d) / 10d);
            item.put("endedAt", doc(session.get("timeLap")).get("endTime"));
            item.put("mode", doc(session.get("session_info")).get("mode"));
            recent.add(item);
        }

        Document defaults = new Document("totalSessions", 0).append("withCouponOrOffer", 0)
                .append("withOffer", 0).append("totalEarned", 0).append("totalRevenue", 0)
                .append("totalExtensionRevenue", 0).append("avgDurMins", 0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overall", overallResult.isEmpty() ? defaults : overallResult.get(0));
        data.put("perCoupon", perCoupon);
        data.put("recentSessions", recent);
        return data;
    }

    private Document conditionalSum(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }
    private Document sumIfNull(String field) {
        return new Document("$sum", new Document("$ifNull", List.of(field, 0)));
    }
    private Date startOfDay(int days) {
        return Date.from(LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L)
                .atStartOfDay().toInstant(ZoneOffset.UTC));
    }
    private Date parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Date.from(Instant.parse(raw)); }
        catch (Exception ignored) {
            try { return Date.from(LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC)); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid date"); }
        }
    }
    private String id(Document actor) {
        Object value = actor.get("_id");
        return value instanceof ObjectId id ? id.toHexString() : String.valueOf(value);
    }
    private Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }
    private double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0d; }
}
