package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ports Node wallet transaction_list and Guide_purchase read paths. */
@Service
public class PaymentHistoryService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final MongoTemplate mongo;
    public PaymentHistoryService(MongoTemplate mongo) { this.mongo = mongo; }

    public Map<String, Object> list(Document actor, String actorType, Integer pageParam,
                                    Integer limitParam, String filter) {
        int page = pageParam == null ? 1 : Math.max(1, pageParam);
        int defaultLimit = "cons".equals(actorType) ? 10 : 100;
        int limit = limitParam == null ? defaultLimit : Math.max(1, limitParam);
        Document match = "cons".equals(actorType)
                ? new Document("consultantId", actor.get("_id")).append("userType", "cons")
                : new Document("userId", actor.get("_id")).append("userType", "user");
        if ("cons".equals(actorType)) {
            Document dates = dateFilter(filter);
            if (dates != null) match.putAll(dates);
        }
        List<Document> result = mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", match),
                new Document("$facet", new Document("list", List.of(
                        new Document("$sort", new Document("createdAt", -1)),
                        new Document("$skip", (page - 1) * limit), new Document("$limit", limit),
                        lookup(Collections.USERS, "userId", "name", "referId", "userId"),
                        lookup(Collections.CONSULTANTS, "consultantId", "name", "userId", "referId"),
                        new Document("$unwind", new Document("path", "$userId").append("preserveNullAndEmptyArrays", true)),
                        new Document("$unwind", new Document("path", "$consultantId").append("preserveNullAndEmptyArrays", true))))
                        .append("count", List.of(new Document("$count", "total"))))))
                .into(new ArrayList<>());
        if (result.isEmpty()) return Map.of("list", List.of(), "total", 0);
        Document facet = result.get(0);
        List<Document> list = facet.getList("list", Document.class, List.of());
        List<Document> count = facet.getList("count", Document.class, List.of());
        Number total = count.isEmpty() ? 0 : count.get(0).get("total", Number.class);
        return Map.of("list", list, "total", total == null ? 0 : total.longValue());
    }

    public Map<String, Object> guidePurchase() {
        Document master = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "userMasterSettings")).first();
        if (master == null) throw new IllegalArgumentException("Guide purchase not found");
        Document data = master.get("data", Document.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rechargeDescription", data == null ? null : data.get("rechargeDescription"));
        out.put("rechargeVideoUrl", data == null ? null : data.get("rechargeVideoUrl"));
        return Map.of("success", true, "message", "Guide purchased successfully", "data", out);
    }

    private Document lookup(String from, String local, String... fields) {
        Document projection = new Document();
        for (String field : fields) projection.put(field, 1);
        return new Document("$lookup", new Document("from", from).append("let", new Document("id", "$" + local))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$eq", List.of("$$id", "$_id")))),
                        new Document("$project", projection))).append("as", local));
    }

    private Document dateFilter(String filter) {
        if (filter == null || filter.isBlank()) return null;
        LocalDate now = LocalDate.now(IST), start, end;
        switch (filter.toLowerCase(Locale.ENGLISH)) {
            case "today", "daywise" -> { start = now; end = now.plusDays(1); }
            case "yesterday" -> { start = now.minusDays(1); end = now; }
            case "weekly", "week" -> {
                start = now.with(DayOfWeek.MONDAY); end = start.plusWeeks(1);
            }
            case "lastmonth" -> {
                YearMonth previous = YearMonth.from(now).minusMonths(1);
                start = previous.atDay(1); end = previous.plusMonths(1).atDay(1);
            }
            case "last6months" -> {
                YearMonth current = YearMonth.from(now); start = current.minusMonths(6).atDay(1); end = current.atDay(1);
            }
            default -> {
                Integer month = month(filter);
                if (month == null) return null;
                int year = month > now.getMonthValue() ? now.getYear() - 1 : now.getYear();
                start = LocalDate.of(year, month, 1); end = start.plusMonths(1);
            }
        }
        return new Document("createdAt", new Document("$gte", Date.from(start.atStartOfDay(IST).toInstant()))
                .append("$lt", Date.from(end.atStartOfDay(IST).toInstant())));
    }

    private Integer month(String value) {
        return switch (value.toLowerCase(Locale.ENGLISH)) {
            case "jan" -> 1; case "feb" -> 2; case "mar" -> 3; case "apr" -> 4;
            case "may" -> 5; case "jun" -> 6; case "jul" -> 7; case "aug" -> 8;
            case "sep", "sept" -> 9; case "oct" -> 10; case "nov" -> 11; case "dec" -> 12;
            default -> null;
        };
    }
}
