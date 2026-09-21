package com.vedicmeet.appserver.banner;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of the consultant home-banner metric helpers in Node banner.js
 * (CONSULTANT_BANNER_CONFIG, day range, waitlist pipelines, loadConsultantBannerMetrics,
 * buildConsultantBannerData + formatting helpers). Read-only (waitlists, stats,
 * seed_masters).
 */
@Service
public class BannerMetricsService {

    // CONSULTANT_BANNER_CONFIG
    private static final int GOAL_PERCENTAGE = 15;
    private static final int ONLINE_GOAL_HOURS = 24;
    private static final int CONVERSION_MIN_COMPLETIONS = 3;

    private final MongoTemplate mongo;

    public BannerMetricsService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** { dayStart: local 00:00:00.000, dayEnd: local 23:59:59.999 }. */
    private Date[] dayRange() {
        Calendar s = Calendar.getInstance();
        s.set(Calendar.HOUR_OF_DAY, 0); s.set(Calendar.MINUTE, 0); s.set(Calendar.SECOND, 0); s.set(Calendar.MILLISECOND, 0);
        Calendar e = Calendar.getInstance();
        e.set(Calendar.HOUR_OF_DAY, 23); e.set(Calendar.MINUTE, 59); e.set(Calendar.SECOND, 59); e.set(Calendar.MILLISECOND, 999);
        return new Date[]{s.getTime(), e.getTime()};
    }

    private Document todayFilter(String consultantIdStr, Date dayStart, Date dayEnd) {
        return new Document("consultant_id", consultantIdStr)
                .append("createdAt", new Document("$gte", dayStart).append("$lt", dayEnd));
    }

    private long aggregateCount(List<Document> pipeline) {
        List<Document> rows = mongo.getCollection(Collections.WAITLISTS)
                .aggregate(pipeline).into(new ArrayList<>());
        if (rows.isEmpty()) return 0;
        Number t = rows.get(0).get("total", Number.class);
        return t == null ? 0 : t.longValue();
    }

    private int percentOf(long numerator, long denominator) {
        if (denominator == 0) return 0;
        return (int) Math.round((numerator / (double) denominator) * 100);
    }

    private String formatOnlineDurationSeconds(long totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "s";
        if (totalSeconds < 3600) return oneDp(totalSeconds / 60.0) + "m";
        if (totalSeconds < 86400) return oneDp(totalSeconds / 3600.0) + "h";
        return oneDp(totalSeconds / 86400.0) + "d";
    }

    private String oneDp(double v) {
        return String.format("%.1f", v);
    }

    /** Parallel-in-Node reads done sequentially here (same queries, same results). */
    public Map<String, Object> loadConsultantBannerMetrics(String consultantIdStr, ObjectId userId) {
        Date[] range = dayRange();
        Date dayStart = range[0], dayEnd = range[1];
        Document today = todayFilter(consultantIdStr, dayStart, dayEnd);

        long totalAttempts = mongo.getCollection(Collections.WAITLISTS).countDocuments(today);

        // missed-by-consultant pipeline
        List<Object> actionByIn = new ArrayList<>();
        if (consultantIdStr != null) actionByIn.add(consultantIdStr);
        if (userId != null) actionByIn.add(userId);
        List<Document> missedPipeline = Arrays.asList(
                new Document("$match", new Document(today).append("logs", new Document("$elemMatch",
                        new Document("callStatus", "missed").append("actionBy", new Document("$in", actionByIn))))),
                new Document("$count", "total"));
        long missedCalls = aggregateCount(missedPipeline);

        // conversions: users whose 3rd completion today falls within the day
        int thirdIndex = CONVERSION_MIN_COMPLETIONS - 1;
        List<Document> convPipeline = Arrays.asList(
                new Document("$match", new Document(today).append("status", "completed")),
                new Document("$sort", new Document("user_id", 1).append("createdAt", 1)),
                new Document("$group", new Document("_id", "$user_id")
                        .append("completionDates", new Document("$push", "$createdAt"))),
                new Document("$match", new Document("$expr",
                        new Document("$gte", Arrays.asList(new Document("$size", "$completionDates"), CONVERSION_MIN_COMPLETIONS)))),
                new Document("$project", new Document("thirdCompletionAt",
                        new Document("$arrayElemAt", Arrays.asList("$completionDates", thirdIndex)))),
                new Document("$match", new Document("thirdCompletionAt",
                        new Document("$gte", dayStart).append("$lt", dayEnd))),
                new Document("$count", "total"));
        long conversions = aggregateCount(convPipeline);

        // online time from stats
        List<Document> onlineStats = mongo.getCollection(Collections.STATS)
                .find(new Document("userType", "cons").append("userID", userId).append("for", "app_online")
                        .append("updatedAt", new Document("$gte", dayStart).append("$lte", dayEnd)))
                .projection(new Document("totalTime", 1))
                .into(new ArrayList<>());
        long totalOnlineTime = 0;
        for (Document row : onlineStats) {
            Number tt = row.get("totalTime", Number.class);
            if (tt != null) totalOnlineTime += tt.longValue();
        }

        int progressMissed = percentOf(missedCalls, totalAttempts);
        int progressConv = percentOf(conversions, totalAttempts);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAttempts", totalAttempts);
        m.put("missedCalls", missedCalls);
        m.put("conversions", conversions);
        m.put("totalOnlineTime", totalOnlineTime);
        m.put("progressMissed", progressMissed);
        m.put("progressConv", progressConv);
        m.put("missedGoalLabel", Math.max(0, progressMissed - GOAL_PERCENTAGE));
        m.put("conversionGoalLabel", Math.min(100, progressConv + GOAL_PERCENTAGE));
        m.put("onlineGoalHours", ONLINE_GOAL_HOURS);
        return m;
    }

    private String todaySummaryFromConversionProgress(int progressConv) {
        int p = Math.min(100, Math.max(0, progressConv));
        if (p < 25) return "Very low conversions";
        if (p < 50) return "Keep improving conversions";
        if (p < 75) return "Good conversion progress";
        return "Excellent conversions today";
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildConsultantBannerData(Map<String, Object> metrics) {
        Document seed = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "consultant_home_banner_data")).first();
        Document data = seed == null ? null : (Document) seed.get("data");

        int progressConv = ((Number) metrics.get("progressConv")).intValue();
        int progressMissed = ((Number) metrics.get("progressMissed")).intValue();

        Map<String, Object> missed = new LinkedHashMap<>();
        missed.put("id", "missed-opportunity");
        missed.put("title", "Missed\nOpportunity");
        missed.put("goal", "Today's Goal : " + metrics.get("missedGoalLabel") + "%");
        missed.put("progress", progressMissed);
        missed.put("content", data == null ? null : data.get("missed_opportunity"));
        missed.put("details", Arrays.asList(
                detail("Total Attempts", metrics.get("totalAttempts")),
                detail("Total Missed Calls", metrics.get("missedCalls"))));

        Map<String, Object> conv = new LinkedHashMap<>();
        conv.put("id", "today-conversions");
        conv.put("title", "Today's\nConversions");
        conv.put("goal", "Today's Goal : " + metrics.get("conversionGoalLabel") + "%");
        conv.put("progress", progressConv);
        conv.put("content", data == null ? null : data.get("today_conversions"));
        conv.put("details", Arrays.asList(
                detail("Total Attempts", metrics.get("totalAttempts")),
                detail("Total Conversions", metrics.get("conversions"))));

        Map<String, Object> online = new LinkedHashMap<>();
        online.put("id", "online-time");
        online.put("title", "Online\nTime");
        online.put("type", "show-text");
        online.put("goal", "Today's Goal : " + metrics.get("onlineGoalHours") + "Hours");
        online.put("progress", formatOnlineDurationSeconds(((Number) metrics.get("totalOnlineTime")).longValue()));
        online.put("content", data == null ? null : data.get("online_time"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todaySummary", todaySummaryFromConversionProgress(progressConv));
        out.put("bannerList", Arrays.asList(missed, conv, online));
        return out;
    }

    private Map<String, Object> detail(String label, Object value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("label", label);
        d.put("value", value);
        return d;
    }
}
