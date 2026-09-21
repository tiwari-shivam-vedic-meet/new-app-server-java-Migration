package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminEnhancedDashboardService {

    private final MongoTemplate mongo;
    @SuppressWarnings("unused")
    private final AdminMongoSupport support;

    public AdminEnhancedDashboardService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> getRevenueAnalytics(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:15 accepts period='daily' but never uses it.
        List<Document> revenueByService = aggregate(Collections.WALLET_TRANSACTIONS, revenueByServicePipeline(start, end));
        List<Document> dailyRevenue = aggregate(Collections.WALLET_TRANSACTIONS, dailyRevenuePipeline(start, end));

        ZonedDateTime currentMonth = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime lastMonth = currentMonth.minusMonths(1).withDayOfMonth(1);
        Map<String, Object> monthlyComparison = new LinkedHashMap<>();
        monthlyComparison.put("current", getMonthlyRevenue(currentMonth.getYear(), currentMonth.getMonthValue() - 1));
        monthlyComparison.put("previous", getMonthlyRevenue(lastMonth.getYear(), lastMonth.getMonthValue() - 1));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revenueByService", revenueByService);
        result.put("dailyRevenue", dailyRevenue);
        result.put("monthlyComparison", monthlyComparison);
        result.put("revenueGrowth", calculateRevenueGrowth(start, end));
        result.put("totalRevenue", dailyRevenue.stream().mapToDouble(item -> doubleNumber(item.get("totalRevenue"))).sum());
        result.put("totalTransactions", dailyRevenue.stream().mapToLong(item -> longNumber(item.get("count"))).sum());
        return result;
    }

    public Map<String, Object> getUserAnalytics(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));
        List<Document> userGrowth = aggregate(Collections.USERS, userGrowthPipeline(start, end));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userGrowth", userGrowth);
        result.put("userSegmentation", getUserSegmentation());
        result.put("userRetention", calculateUserRetention(start));
        result.put("activeUsers", getActiveUsers(start, end));
        result.put("userSatisfaction", getUserSatisfaction());
        result.put("totalUsers", mongo.getCollection(Collections.USERS).countDocuments());
        result.put("newUsers", userGrowth.stream().mapToLong(item -> longNumber(item.get("newUsers"))).sum());
        return result;
    }

    public Map<String, Object> getConsultantPerformance(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topPerformers", aggregate(Collections.WAITLISTS, topPerformersPipeline(start, end)));
        result.put("ratingDistribution", aggregate(Collections.WAITLISTS, ratingDistributionPipeline(start, end)));
        result.put("sessionTypes", aggregate(Collections.WAITLISTS, sessionTypesPipeline(start, end)));
        result.put("availability", getConsultantAvailability());
        result.put("totalConsultants", mongo.getCollection(Collections.CONSULTANTS).countDocuments());
        result.put("activeConsultants", mongo.getCollection(Collections.CONSULTANTS).countDocuments(Document.parse("{ \"status\": 1 }")));
        return result;
    }

    public Map<String, Object> getRealtimeAnalytics() {
        Date now = new Date();
        Date oneHourAgo = new Date(now.getTime() - 60L * 60L * 1000L);
        long activeUsers = mongo.getCollection(Collections.WAITLISTS).countDocuments(Document.parse("{ \"createdAt\": { \"$gte\": " + dateJson(oneHourAgo) + " }, \"status\": { \"$in\": [\"progress\", \"waiting\"] } }"));
        long onlineConsultants = mongo.getCollection(Collections.BROADCASTS).countDocuments(Document.parse("{ \"status\": 1 }"));
        long activeSessions = mongo.getCollection(Collections.WAITLISTS).countDocuments(Document.parse("{ \"status\": \"progress\" }"));
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:266-267 today's revenue starts at UTC midnight, not local midnight.
        Date todayStart = Date.from(ZonedDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        List<Document> todayRevenue = aggregate(Collections.WALLET_TRANSACTIONS, todayRevenuePipeline(todayStart));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeUsers", activeUsers);
        result.put("onlineConsultants", onlineConsultants);
        result.put("activeSessions", activeSessions);
        result.put("todayRevenue", todayRevenue.isEmpty() ? 0 : orZero(todayRevenue.get(0).get("totalRevenue")));
        result.put("timestamp", now);
        return result;
    }

    public List<Document> getServiceDistribution(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));
        return aggregate(Collections.WAITLISTS, serviceDistributionPipeline(start, end));
    }

    public List<Document> getUserGrowthTrends(Map<String, String> query) {
        String period = value(query, "period", "monthly");
        String groupFormat = "%Y-%m";
        if ("daily".equals(period)) groupFormat = "%Y-%m-%d";
        else if ("weekly".equals(period)) groupFormat = "%Y-%U";
        return aggregate(Collections.USERS, userGrowthTrendsPipeline(groupFormat));
    }

    public List<Document> getConsultantEarnings(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));
        return aggregate(Collections.WAITLISTS, consultantEarningsPipeline(start, end));
    }

    public List<Document> getSessionAnalytics(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));
        return aggregate(Collections.WAITLISTS, sessionAnalyticsPipeline(start, end));
    }

    public Map<String, Object> getSupportAnalytics(Map<String, String> query) {
        Date start = jsDate(value(query, "startDate", null));
        Date end = utcEndOfDay(jsDate(value(query, "endDate", null)));
        List<Document> analytics = aggregate(Collections.CUSTOMER_SUPPORT_QUERIES, supportAnalyticsPipeline(start, end));
        long totalQueries = analytics.stream().mapToLong(item -> longNumber(item.get("count"))).sum();
        long resolvedQueries = analytics.stream().filter(item -> "resolved".equals(item.get("_id"))).findFirst().map(item -> longNumber(item.get("count"))).orElse(0L);
        long pendingQueries = analytics.stream().filter(item -> "pending".equals(item.get("_id"))).findFirst().map(item -> longNumber(item.get("count"))).orElse(0L);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalQueries", totalQueries);
        result.put("resolvedQueries", resolvedQueries);
        result.put("pendingQueries", pendingQueries);
        result.put("resolutionRate", totalQueries > 0 ? (resolvedQueries / (double) totalQueries) * 100 : 0);
        result.put("analytics", analytics);
        return result;
    }

    List<Document> revenueByServicePipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"transactionType\": 1, \"$or\": [ { \"transactionFor\": \"membership\" }, { \"transactionFor\": \"consult\" }, { \"transactionFor\": \"meditation\" }, { \"transactionFor\": \"vastu\" }, { \"transactionFor\": \"shopify\" } ] } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": \"$transactionFor\", \"totalRevenue\": { \"$sum\": { \"$toDouble\": \"$totalAmountPayToPlateform\" } }, \"count\": { \"$sum\": 1 } } }"));
        return pipeline;
    }

    List<Document> dailyRevenuePipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"transactionType\": 1 } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"$dateToString\": { \"format\": \"%Y-%m-%d\", \"date\": \"$createdAt\" } }, \"totalRevenue\": { \"$sum\": { \"$toDouble\": \"$totalAmountPayToPlateform\" } }, \"count\": { \"$sum\": 1 } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"_id\": 1 } }"));
        return pipeline;
    }

    List<Document> userGrowthPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " } } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"$dateToString\": { \"format\": \"%Y-%m-%d\", \"date\": \"$createdAt\" } }, \"newUsers\": { \"$sum\": 1 } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"_id\": 1 } }"));
        return pipeline;
    }

    List<Document> topPerformersPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"status\": \"completed\" } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": \"$consultant_id\", \"totalSessions\": { \"$sum\": 1 }, \"totalEarnings\": { \"$sum\": { \"$toDouble\": \"$session_info.coins\" } }, \"avgRating\": { \"$avg\": { \"$toDouble\": \"$session_info.rating\" } } } }"));
        pipeline.add(Document.parse("{ \"$lookup\": { \"from\": \"consultants\", \"localField\": \"_id\", \"foreignField\": \"_id\", \"as\": \"consultant\" } }"));
        pipeline.add(Document.parse("{ \"$unwind\": \"$consultant\" }"));
        pipeline.add(Document.parse("{ \"$project\": { \"consultantName\": { \"$concat\": [\"$consultant.firstName\", \" \", \"$consultant.lastName\"] }, \"totalSessions\": 1, \"totalEarnings\": 1, \"avgRating\": { \"$round\": [\"$avgRating\", 1] } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"totalEarnings\": -1 } }"));
        pipeline.add(Document.parse("{ \"$limit\": 10 }"));
        return pipeline;
    }

    List<Document> ratingDistributionPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"status\": \"completed\", \"session_info.rating\": { \"$exists\": true, \"$ne\": null } } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"$round\": [\"$session_info.rating\", 0] }, \"count\": { \"$sum\": 1 } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"_id\": -1 } }"));
        return pipeline;
    }

    List<Document> sessionTypesPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"status\": \"completed\" } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": \"$used_for\", \"count\": { \"$sum\": 1 } } }"));
        return pipeline;
    }

    List<Document> todayRevenuePipeline(Date todayStart) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(todayStart) + " }, \"transactionType\": 1 } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": null, \"totalRevenue\": { \"$sum\": { \"$toDouble\": \"$totalAmountPayToPlateform\" } } } }"));
        return pipeline;
    }

    List<Document> serviceDistributionPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"status\": \"completed\" } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": \"$used_for\", \"count\": { \"$sum\": 1 }, \"totalRevenue\": { \"$sum\": { \"$toDouble\": \"$session_info.coins\" } } } }"));
        return pipeline;
    }

    List<Document> userGrowthTrendsPipeline(String groupFormat) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"$dateToString\": { \"format\": \"" + escapeJson(groupFormat) + "\", \"date\": \"$createdAt\" } }, \"newUsers\": { \"$sum\": 1 } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"_id\": 1 } }"));
        return pipeline;
    }

    List<Document> consultantEarningsPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"status\": \"completed\" } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"$dateToString\": { \"format\": \"%Y-%m-%d\", \"date\": \"$createdAt\" } }, \"totalEarnings\": { \"$sum\": { \"$toDouble\": \"$session_info.coins\" } }, \"sessionCount\": { \"$sum\": 1 } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"_id\": 1 } }"));
        return pipeline;
    }

    List<Document> sessionAnalyticsPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " } } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"type\": \"$used_for\", \"status\": \"$status\" }, \"count\": { \"$sum\": 1 }, \"avgDuration\": { \"$avg\": { \"$toDouble\": \"$session_info.duration\" } } } }"));
        return pipeline;
    }

    List<Document> supportAnalyticsPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " } } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": \"$status\", \"count\": { \"$sum\": 1 } } }"));
        return pipeline;
    }

    List<Document> monthlyRevenuePipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"transactionType\": 1 } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": { \"$week\": \"$createdAt\" }, \"totalRevenue\": { \"$sum\": { \"$toDouble\": \"$totalAmountPayToPlateform\" } } } }"));
        pipeline.add(Document.parse("{ \"$sort\": { \"_id\": 1 } }"));
        return pipeline;
    }

    List<Document> revenueForPeriodPipeline(Date start, Date end) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"createdAt\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " }, \"transactionType\": 1 } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": null, \"totalRevenue\": { \"$sum\": { \"$toDouble\": \"$totalAmountPayToPlateform\" } } } }"));
        return pipeline;
    }

    List<Document> userSatisfactionPipeline() {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(Document.parse("{ \"$match\": { \"session_info.rating\": { \"$exists\": true, \"$ne\": null } } }"));
        pipeline.add(Document.parse("{ \"$group\": { \"_id\": null, \"avgRating\": { \"$avg\": { \"$toDouble\": \"$session_info.rating\" } }, \"totalRatings\": { \"$sum\": 1 } } }"));
        return pipeline;
    }

    private List<Document> getMonthlyRevenue(int year, int zeroBasedMonth) {
        Date start = jsLocalDate(year, zeroBasedMonth, 1);
        Date end = utcEndOfDay(jsLocalDate(year, zeroBasedMonth + 1, 0));
        return aggregate(Collections.WALLET_TRANSACTIONS, monthlyRevenuePipeline(start, end));
    }

    private Map<String, Object> calculateRevenueGrowth(Date start, Date end) {
        Date midPoint = new Date(start.getTime() + (end.getTime() - start.getTime()) / 2);
        double firstHalf = getRevenueForPeriod(start, midPoint);
        double secondHalf = getRevenueForPeriod(midPoint, end);
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:492-495 midpoint is included in both halves via $lte/$gte.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("growthRate", firstHalf > 0 ? ((secondHalf - firstHalf) / firstHalf) * 100 : 0);
        result.put("firstHalf", firstHalf);
        result.put("secondHalf", secondHalf);
        return result;
    }

    private double getRevenueForPeriod(Date start, Date end) {
        List<Document> result = aggregate(Collections.WALLET_TRANSACTIONS, revenueForPeriodPipeline(start, end));
        return result.isEmpty() ? 0 : doubleNumber(result.get(0).get("totalRevenue"));
    }

    private Map<String, Object> getUserSegmentation() {
        Map<String, Object> result = new LinkedHashMap<>();
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:521-523 free users require membershipType to be missing, not null/blank.
        result.put("freeUsers", mongo.getCollection(Collections.USERS).countDocuments(Document.parse("{ \"membershipType\": { \"$exists\": false } }")));
        result.put("silverMembers", mongo.getCollection(Collections.USERS).countDocuments(Document.parse("{ \"membershipType\": \"SILVER\" }")));
        result.put("goldMembers", mongo.getCollection(Collections.USERS).countDocuments(Document.parse("{ \"membershipType\": \"GOLD\" }")));
        return result;
    }

    private Map<String, Object> calculateUserRetention(Date start) {
        long totalUsers = mongo.getCollection(Collections.USERS).countDocuments(Document.parse("{ \"createdAt\": { \"$lte\": " + dateJson(start) + " } }"));
        long activeUsers = mongo.getCollection(Collections.USERS).countDocuments(Document.parse("{ \"createdAt\": { \"$lte\": " + dateJson(start) + " }, \"lastLogin\": { \"$gte\": " + dateJson(start) + " } }"));
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:530-536 accepts end but ignores it for retention.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", totalUsers);
        result.put("activeUsers", activeUsers);
        result.put("retentionRate", totalUsers > 0 ? (activeUsers / (double) totalUsers) * 100 : 0);
        return result;
    }

    private long getActiveUsers(Date start, Date end) {
        return mongo.getCollection(Collections.USERS).countDocuments(Document.parse("{ \"lastLogin\": { \"$gte\": " + dateJson(start) + ", \"$lte\": " + dateJson(end) + " } }"));
    }

    private Object getUserSatisfaction() {
        List<Document> result = aggregate(Collections.WAITLISTS, userSatisfactionPipeline());
        if (!result.isEmpty()) return result.get(0);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("avgRating", 0);
        fallback.put("totalRatings", 0);
        return fallback;
    }

    private long getConsultantAvailability() {
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:572-573 consultant availability uses local midnight, unlike revenue date bounds.
        Date today = Date.from(LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant());
        return mongo.getCollection(Collections.CONSULTANTS).countDocuments(Document.parse("{ \"status\": 1, \"lastSeen\": { \"$gte\": " + dateJson(today) + " } }"));
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        return mongo.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private String value(Map<String, String> query, String key, String fallback) {
        String value = query == null ? null : query.get(key);
        return value == null ? fallback : value;
    }

    private Date jsDate(String raw) {
        if (raw == null) return new Date();
        try {
            return Date.from(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            try {
                return Date.from(OffsetDateTime.parse(raw).toInstant());
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return Date.from(LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC));
                } catch (DateTimeParseException ignoredThird) {
                    // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:18-20 Invalid Date reaches BSON as epoch 0 in the Node driver.
                    return new Date(0);
                }
            }
        }
    }

    private Date jsLocalDate(int year, int zeroBasedMonth, int dayOfMonth) {
        LocalDate date = LocalDate.of(year, 1, 1).plusMonths(zeroBasedMonth).plusDays(dayOfMonth - 1L);
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private Date utcEndOfDay(Date date) {
        // FAITHFUL(node-quirk): utils/classes/enhanced-dashboard.js:20 uses setUTCHours, so local date inputs end on the UTC day, not local day.
        LocalDate utcDay = date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        return Date.from(utcDay.atTime(23, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC));
    }

    private String dateJson(Date date) {
        return "{ \"$date\": \"" + date.toInstant() + "\" }";
    }

    private Object orZero(Object value) {
        return value == null ? 0 : value;
    }

    private double doubleNumber(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private long longNumber(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
