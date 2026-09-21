package com.vedicmeet.appserver.consultant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Consultant mobile read/query surface from rest-apis/modules/consultant/consultant.js. */
@Service
public class ConsultantMobileReadService {

    private static final ZoneOffset ZONE = ZoneOffset.UTC;
    private static final Set<String> MONEY_FIELDS = Set.of(
            "couponCode", "couponDiscountAmount", "consultantChargeCoinMin",
            "consultantFinalCallChargeCoinMin", "isUserHaveActiveMembership",
            "membershipDiscountAmount", "totalAmountPayToConsultant", "totalAmountPayToPlateform");

    private final MongoTemplate mongo;
    private final AppConstants constants;
    private final ObjectMapper mapper;

    public ConsultantMobileReadService(MongoTemplate mongo, AppConstants constants, ObjectMapper mapper) {
        this.mongo = mongo;
        this.constants = constants;
        this.mapper = mapper;
    }

    public Map<String, Object> availability(String consultantId, String requestedDate) {
        ObjectId id = id(consultantId, "CONSULTANT_ID_REQUIRED");
        LocalDate from = parseDate(requestedDate);
        if (from == null) from = LocalDate.now(ZONE);
        Bson date = blank(requestedDate)
                ? Filters.gte("date", Date.from(from.atStartOfDay().toInstant(ZONE)))
                : Filters.and(Filters.gte("date", Date.from(from.atStartOfDay().toInstant(ZONE))),
                        Filters.lt("date", Date.from(from.plusDays(1).atStartOfDay().toInstant(ZONE))));
        Map<String, List<Document>> grouped = new LinkedHashMap<>();
        grouped.put("CALL", new ArrayList<>()); grouped.put("CHAT", new ArrayList<>());
        grouped.put("VIDEO", new ArrayList<>());
        mongo.getCollection(Collections.CONSULTANT_AVAILABLES)
                .find(Filters.and(Filters.eq("consultantId", id), date))
                .sort(Sorts.ascending("date"))
                .forEach(slot -> grouped.computeIfAbsent(text(slot.get("type")).toUpperCase(Locale.ROOT),
                        ignored -> new ArrayList<>()).add(slot));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("call", groupedAvailability(grouped.get("CALL")));
        result.put("chat", groupedAvailability(grouped.get("CHAT")));
        result.put("video", groupedAvailability(grouped.get("VIDEO")));
        return result;
    }

    public Map<String, Object> fixedSessions(String consultantId) {
        ObjectId id = id(consultantId, "CONSULTANT_ID_REQUIRED");
        List<Document> all = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("consultant_id", id).append("used_for", "session"))
                .projection(Projections.include("status", "session_info.sessionMeta", "onCompletion", "createdAt"))
                .into(new ArrayList<>());
        Comparator<Document> ascending = Comparator.comparing(this::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        List<Document> waiting = all.stream().filter(d -> "waiting".equals(d.getString("status")))
                .sorted(ascending).toList();
        List<Document> others = all.stream().filter(d -> !"waiting".equals(d.getString("status")))
                .sorted(ascending.reversed()).toList();
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Document item : concat(waiting, others)) {
            Document meta = doc(doc(item.get("session_info")).get("sessionMeta"));
            String from = text(meta.get("timeFrom"));
            String to = text(meta.get("timeTo"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", item.get("status")); row.put("title", "Fixed Session");
            row.put("startTime", meta.get("timeFrom")); row.put("timeTo", meta.get("timeTo"));
            row.put("duration", minutesBetween(from, to)); row.put("date", item.get("createdAt"));
            sessions.add(row);
        }
        LocalDate today = LocalDate.now(ZONE);
        Document availability = mongo.getCollection(Collections.CONSULTANT_AVAILABLES)
                .find(new Document("consultantId", id).append("date", new Document("$gte",
                        Date.from(today.atStartOfDay().toInstant(ZONE))).append("$lt",
                        Date.from(today.plusDays(1).atStartOfDay().toInstant(ZONE))))).first();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_fixedSession", sessions); result.put("consultantAvailable", availability);
        return result;
    }

    public List<Document> consultantWaitlist(Document actor) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        Date tomorrow = Date.from(LocalDate.now(ZONE).plusDays(1).atStartOfDay().toInstant(ZONE));
        List<Document> list = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .find(new Document("consultantId", consultantId).append("isConsultantCompleted", "waiting")
                        .append("createdAt", new Document("$lt", tomorrow)))
                .into(new ArrayList<>());
        list.forEach(item -> MONEY_FIELDS.forEach(item::remove));
        // Array.sort is stable in current Node; preserve its second-sort precedence exactly.
        Map<String, Integer> planOrder = Map.of("GOLD", 0, "SILVER", 1, "OTHERS", 2);
        list.sort(Comparator.comparingInt(item -> planOrder.getOrDefault(text(item.get("planType")), 3)));
        list.sort(Comparator.comparingInt((Document item) -> bool(item.get("isEmergency")) ? 1 : 0).reversed());
        long elapsed = 0;
        for (Document item : list) {
            item.put("waitTimeSecond", elapsed);
            elapsed += longNumber(item.get("totalCallSeconds"));
        }
        return list;
    }

    public Document kundaliFromRequestForm(String requestFormId) {
        ObjectId id = id(requestFormId, "KUNDLI_NOT_EXIST");
        Document result = mongo.getCollection(Collections.USER_KUNDALIS)
                .find(new Document("userId", id)).first();
        if (result == null) throw new IllegalArgumentException("KUNDLI_NOT_EXIST");
        return result;
    }

    public Map<String, Object> orderMessages(String roomId) {
        ObjectId id = id(roomId, "ROOM_ID_REQUIRED");
        List<Document> chats = mongo.getCollection(Collections.CHATS)
                .find(new Document("roomId", id)).sort(Sorts.ascending("createdAt")).into(new ArrayList<>());
        return Map.of("chats", chats);
    }

    public Map<String, Object> orderHistory(Document actor, int page, int limit, String search,
                                            String listFor, String typeOfConsult, String type) {
        ObjectId actorId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        int safeLimit = clamp(limit, 1, 100);
        int skip = Math.max(0, page - 1) * safeLimit;
        Document match = new Document("isConsultantCompleted", new Document("$in", List.of("progress", "complete")));
        if ("user".equals(listFor)) match.put("userId", actorId);
        else {
            match.put("consultantId", actorId);
            if (blank(type) && !blank(typeOfConsult)) match.put("typeOfConsult", typeOfConsult);
        }
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", match));
        pipeline.add(lookup(Collections.CONSULTANTS, "consultantId", "consultantDetail"));
        pipeline.add(unwind("consultantDetail", true));
        pipeline.add(lookup(Collections.USERS, "userId", "userDetails"));
        pipeline.add(unwind("userDetails", true));
        pipeline.add(new Document("$lookup", new Document("from", Collections.REVIEW_AND_RATINGS)
                .append("localField", "_id").append("foreignField", "consultantRequestFormId")
                .append("as", "ratingReviewList")));
        if (!blank(search)) {
            Pattern regex = Pattern.compile(Pattern.quote(search), Pattern.CASE_INSENSITIVE);
            pipeline.add(new Document("$match", new Document("$or", List.of(
                    new Document("firstName", regex), new Document("lastName", regex),
                    new Document("mobileNumber", regex), new Document("consultantDetail.name", regex)))));
        }
        pipeline.add(new Document("$facet", new Document("total", List.of(new Document("$count", "value")))
                .append("list", List.of(new Document("$sort", new Document("createdAt", -1)),
                        new Document("$skip", skip), new Document("$limit", safeLimit)))));
        Document facet = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).aggregate(pipeline).first();
        List<Document> list = docs(facet == null ? null : facet.get("list"));
        Instant now = Instant.now();
        for (Document item : list) {
            Instant updated = instant(item.get("updatedAt"));
            item.put("isRefundButtonAccessable", updated != null && Duration.between(updated, now).toHours() < 24);
        }
        List<Document> totalRows = docs(facet == null ? null : facet.get("total"));
        long total = totalRows.isEmpty() ? 0 : longNumber(totalRows.get(0).get("value"));
        return Map.of("totalOrder", total, "totalOrderList", list);
    }

    public List<Document> onlineRanking(int page, int limit) {
        List<String> days = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d-M-yyyy");
        for (int i = 6; i >= 0; i--) days.add(LocalDate.now(ZONE).minusDays(i).format(formatter));
        int safeLimit = clamp(limit, 1, 100);
        return mongo.getCollection(Collections.STATS).aggregate(List.of(
                new Document("$match", new Document("userType", "cons").append("for", "app_online")
                        .append("day", new Document("$in", days))),
                lookup(Collections.CONSULTANTS, "userID", "consultants"),
                unwind("consultants", false),
                new Document("$group", new Document("_id", "$userID")
                        .append("name", new Document("$first", "$consultants.name"))
                        .append("userName", new Document("$first", "$consultants.accountName"))
                        .append("image", new Document("$first", "$consultants.profileImage"))
                        .append("totalWeeklyOnlineTime", new Document("$sum", "$totalTime"))),
                new Document("$project", new Document("name", new Document("$cond", List.of(
                        new Document("$eq", List.of("$userName", "")), "$name", "$userName")))
                        .append("image", mediaExpression("$image"))
                        .append("totalWeeklyOnlineTime", 1)
                        .append("totalWeeklyOnlineTimeMinutes", new Document("$divide", List.of("$totalWeeklyOnlineTime", 60)))
                        .append("totalWeeklyOnlineTimeHours", new Document("$divide", List.of("$totalWeeklyOnlineTime", 3600)))),
                new Document("$sort", new Document("totalWeeklyOnlineTime", -1)),
                new Document("$skip", Math.max(0, page - 1) * safeLimit), new Document("$limit", safeLimit)
        )).into(new ArrayList<>());
    }

    public List<Document> earningRanking(int page, int limit) {
        LocalDate monday = LocalDate.now(ZONE).with(java.time.temporal.TemporalAdjusters
                .previousOrSame(java.time.DayOfWeek.MONDAY));
        Date start = Date.from(monday.atStartOfDay().toInstant(ZONE));
        Date end = Date.from(monday.plusDays(7).atStartOfDay().toInstant(ZONE));
        int safeLimit = clamp(limit, 1, 100);
        return mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", new Document("userType", "cons")
                        .append("createdAt", new Document("$gte", start).append("$lt", end))),
                new Document("$group", new Document("_id", "$consultantId")
                        .append("totalAmount", new Document("$sum", convertDouble("$coins")))),
                new Document("$sort", new Document("totalAmount", -1)),
                new Document("$skip", Math.max(0, page - 1) * safeLimit), new Document("$limit", safeLimit),
                lookup(Collections.CONSULTANTS, "_id", "consultant"), unwind("consultant", true),
                new Document("$project", new Document("_id", 0)
                        .append("name", new Document("$cond", List.of(
                                new Document("$eq", List.of("$consultant.accountName", "")),
                                "$consultant.name", "$consultant.accountName")))
                        .append("totalWeeklyAmount", "$totalAmount")
                        .append("image", mediaExpression("$consultant.profileImage")))
        )).into(new ArrayList<>());
    }

    public Map<String, Object> priceRequests(Document actor, int page, int limit) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        int safeLimit = clamp(limit, 1, 100);
        Document match = new Document("consultantId", consultantId).append("requestType", "PRICE");
        List<Document> list = mongo.getCollection(Collections.REQUESTS).find(match)
                .sort(Sorts.descending("createdAt")).skip(Math.max(0, page - 1) * safeLimit)
                .limit(safeLimit).into(new ArrayList<>());
        list.forEach(item -> {
            item.put("newData", parseJson(item.get("newData")));
            item.put("currentData", parseJson(item.get("currentData")));
        });
        return Map.of("reqList", list, "total", mongo.getCollection(Collections.REQUESTS).countDocuments(match));
    }

    public Map<String, Object> form16(Document actor, int page, int limit, String search) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        Document match = new Document("status", true).append("consultantId", consultantId);
        if (!blank(search)) match.put("title", Pattern.compile(Pattern.quote(search), Pattern.CASE_INSENSITIVE));
        int safeLimit = clamp(limit, 1, 100);
        List<Document> list = mongo.getCollection(Collections.CONSULTANT_FORMS_16).find(match)
                .sort(Sorts.descending("createdAt")).skip(Math.max(0, page - 1) * safeLimit)
                .limit(safeLimit).projection(Projections.include("title", "file")).into(new ArrayList<>());
        list.forEach(item -> {
            String file = text(item.remove("file"));
            item.put("fileUrl", blank(file) ? "" : constants.mediaUrl + file);
        });
        return Map.of("list", list,
                "total", mongo.getCollection(Collections.CONSULTANT_FORMS_16).countDocuments(match));
    }

    /** GET in Node also marks the messages read; the controller gates this write. */
    public List<Document> broadcastMessages(String broadcastId) {
        ObjectId id = id(broadcastId, "BROADCAST_ID_REQUIRED");
        Document match = new Document("broadcastId", id);
        mongo.getCollection(Collections.BROADCAST_MESSAGES).updateMany(match,
                Updates.combine(Updates.set("isRead", true), Updates.set("updatedAt", new Date())));
        return mongo.getCollection(Collections.BROADCAST_MESSAGES).find(match)
                .sort(Sorts.ascending("createdAt")).into(new ArrayList<>());
    }

    public Map<String, Object> reviews(Document actor, int page, int limit, int filter, String type) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        Document match = new Document("consultantId", consultantId);
        if ("FLAG".equalsIgnoreCase(type)) match.put("isFlag", true);
        if ("PIN".equalsIgnoreCase(type)) match.put("isPin", true);
        if (filter != 0) match.put("rating", filter);
        int safeLimit = clamp(limit, 1, 100);
        List<Document> list = mongo.getCollection(Collections.REVIEW_AND_RATINGS).aggregate(List.of(
                new Document("$match", match),
                new Document("$addFields", new Document("sortDate", new Document("$ifNull", List.of("$customDate", "$createdAt")))),
                new Document("$sort", new Document("sortDate", -1)),
                new Document("$skip", Math.max(0, page - 1) * safeLimit), new Document("$limit", safeLimit),
                lookup(Collections.USERS, "userId", "userDetails"), unwind("userDetails", true),
                lookup(Collections.WAITLISTS, "consultantRequestFormId", "orders"), unwind("orders", true),
                new Document("$lookup", new Document("from", Collections.REVIEW_REPLIES)
                        .append("localField", "_id").append("foreignField", "reviewRatingId")
                        .append("as", "reviewReplyArray")), unwind("reviewReplyArray", true),
                new Document("$addFields", new Document("reviewReply", "$reviewReplyArray")
                        .append("isReplyByConsultant", new Document("$ne", List.of("$reviewReplyArray", null))))
        )).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .countDocuments(new Document(match).append("status", true));
        long flagged = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .countDocuments(new Document(match).append("isFlag", true));
        int oldFlags = intNumber(actor.get("reviewFlagCount"));
        Map<String, Integer> flags = resolvedFlagCounts(consultantId);
        int totalUsed = oldFlags + (int) flagged + flags.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list); result.put("total", total);
        result.put("totalFlagCount", oldFlags + flagged); result.put("missedCallsCount", oldFlags);
        result.put("flagCount", flagged);
        result.put("flags", Map.of("negative_feedback", flags.get("negative_feedback"),
                "missedCallCount", flags.get("missed_call"), "offlineSessionCount", flags.get("offline_session"),
                "totalUsed", totalUsed));
        result.put("flagCountsResult", flags);
        return result;
    }

    public Map<String, Object> reviewAverage(Document actor) {
        ObjectId id = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        List<Document> ratings = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .find(new Document("consultantId", id).append("status", true)
                        .append("rating", new Document("$ne", 0)))
                .projection(Projections.include("rating")).into(new ArrayList<>());
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        double sum = 0;
        for (Document item : ratings) {
            int value = intNumber(item.get("rating")); sum += value; counts.merge(value, 1, Integer::sum);
        }
        List<Map<String, Object>> ratingCounts = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("rating", e.getKey(), "count", e.getValue()))
                .toList();
        return Map.of("ratingAverage", ratings.isEmpty() ? 0 : round(sum / ratings.size(), 2),
                "totalRating", ratings.size(), "ratingCounts", ratingCounts);
    }

    public Map<String, Object> flagCount(Document actor) {
        ObjectId id = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        long count = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .countDocuments(new Document("consultantId", id).append("isFlag", true));
        return Map.of("flagCount", count);
    }

    public Map<String, Object> offerHistory(Document actor, String couponId, int page, int limit) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        Document match = new Document("consultantId", consultantId);
        if (!blank(couponId)) match.put("couponId", objectIdOrString(couponId));
        int safeLimit = clamp(limit, 1, 50);
        int skip = Math.max(0, page - 1) * safeLimit;
        List<Document> list = mongo.getCollection(Collections.COUPON_ACTIVITIES).aggregate(List.of(
                new Document("$match", match), new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skip), new Document("$limit", safeLimit),
                lookup(Collections.COUPONS, "couponId", "couponId"), unwind("couponId", true)
        )).into(new ArrayList<>());
        return Map.of("list", list, "total", mongo.getCollection(Collections.COUPON_ACTIVITIES).countDocuments(match),
                "page", Math.max(1, page), "limit", safeLimit);
    }

    public Map<String, Object> availabilityDashboard(String consultantId) {
        ObjectId id = id(consultantId, "CONSULTANT_ID_REQUIRED");
        Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", id)).projection(Projections.include("limit")).first();
        YearMonth month = YearMonth.now(ZONE);
        Date start = Date.from(month.atDay(1).atStartOfDay().toInstant(ZONE));
        Date end = Date.from(month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZONE));
        long missed = mongo.getCollection(Collections.FLAG_LOGS).countDocuments(
                new Document("consultant_id", id).append("flag_type", "missed_call")
                        .append("createdAt", new Document("$gte", start).append("$lt", end)));
        Date week = Date.from(Instant.now().minusSeconds(7L * 86_400L));
        double onlineSeconds = 0;
        for (Document row : mongo.getCollection(Collections.ONLINE_RECORDS)
                .find(new Document("consultantId", id).append("startTime", new Document("$gte", week)))) {
            Instant from = instant(row.get("startTime"));
            Instant to = instant(row.get("endTime"));
            if (from != null) onlineSeconds += Math.max(0, Duration.between(from, to == null ? Instant.now() : to).toSeconds());
        }
        int flags = intNumber(doc(consultant == null ? null : consultant.get("limit")).getEmbedded(List.of("flags", "current"), Object.class));
        return Map.of("flagCount", flags, "totalOnlineTime", onlineSeconds, "missedCallCount", missed);
    }

    private List<Document> groupedAvailability(List<Document> slots) {
        Map<Object, List<Document>> byDate = new LinkedHashMap<>();
        for (Document slot : slots == null ? List.<Document>of() : slots) {
            Document entry = new Document("_id", slot.get("_id"))
                    .append("consultantId", slot.get("consultantId")).append("type", slot.get("type"))
                    .append("time", slot.get("time")).append("Status", slot.get("Status"));
            byDate.computeIfAbsent(slot.get("date"), ignored -> new ArrayList<>()).add(entry);
        }
        List<Document> result = new ArrayList<>();
        byDate.forEach((date, entries) -> result.add(new Document("_id", date).append("entries", entries)));
        return result;
    }

    private Map<String, Integer> resolvedFlagCounts(ObjectId consultantId) {
        YearMonth month = YearMonth.now(ZONE);
        Date start = Date.from(month.atDay(1).atStartOfDay().toInstant(ZONE));
        Date end = Date.from(month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZONE));
        List<Document> rows = mongo.getCollection(Collections.FLAG_LOGS).aggregate(List.of(
                new Document("$match", new Document("consultant_id", consultantId).append("flag_status", "resolved")
                        .append("createdAt", new Document("$gte", start).append("$lt", end))),
                new Document("$group", new Document("_id", "$flag_type").append("count", new Document("$sum", 1)))
        )).into(new ArrayList<>());
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("negative_feedback", 0); result.put("missed_call", 0); result.put("offline_session", 0);
        rows.forEach(row -> { if (result.containsKey(row.getString("_id"))) result.put(row.getString("_id"), intNumber(row.get("count"))); });
        return result;
    }

    private Object parseJson(Object value) {
        if (value == null || blank(String.valueOf(value))) return null;
        if (!(value instanceof String text)) return value;
        try { return mapper.readValue(text, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception ignored) { return value; }
    }

    private Document mediaExpression(String field) {
        return new Document("$cond", List.of(
                new Document("$or", List.of(new Document("$eq", List.of(field, "")),
                        new Document("$eq", List.of(field, null)),
                        new Document("$regexMatch", new Document("input", new Document("$ifNull", List.of(field, "")))
                                .append("regex", "^https")))),
                new Document("$ifNull", List.of(field, "")),
                new Document("$concat", List.of(constants.mediaUrl, field))));
    }

    private static Document lookup(String from, String local, String as) {
        return new Document("$lookup", new Document("from", from).append("localField", local)
                .append("foreignField", "_id").append("as", as));
    }
    private static Document unwind(String path, boolean preserve) {
        return new Document("$unwind", new Document("path", "$" + path).append("preserveNullAndEmptyArrays", preserve));
    }
    private static Object convertDouble(String field) {
        return new Document("$convert", new Document("input", field).append("to", "double")
                .append("onError", 0).append("onNull", 0));
    }
    private Date createdAt(Document value) { return value.getDate("createdAt"); }
    private static List<Document> concat(List<Document> first, List<Document> second) {
        List<Document> result = new ArrayList<>(first); result.addAll(second); return result;
    }
    private static long minutesBetween(String from, String to) {
        if (blank(from) || blank(to)) return 0;
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_DATE_TIME,
                DateTimeFormatter.ofPattern("HH:mm"), DateTimeFormatter.ofPattern("hh:mm a", Locale.US))) {
            try {
                if (formatter == DateTimeFormatter.ISO_DATE_TIME) {
                    return Math.abs(Duration.between(LocalDateTime.parse(from, formatter), LocalDateTime.parse(to, formatter)).toMinutes());
                }
                return Math.abs(Duration.between(LocalTime.parse(from, formatter), LocalTime.parse(to, formatter)).toMinutes());
            } catch (Exception ignored) { }
        }
        return 0;
    }
    private static LocalDate parseDate(String value) {
        if (blank(value)) return null;
        try { return Instant.parse(value).atZone(ZONE).toLocalDate(); }
        catch (Exception ignored) {
            try { return LocalDate.parse(value); } catch (Exception invalid) { throw new IllegalArgumentException("INVALID_DATE"); }
        }
    }
    private static ObjectId id(Object value, String error) {
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException(error);
    }
    private static Object objectIdOrString(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }
    @SuppressWarnings("unchecked")
    private static Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) {
            Document d = new Document(); map.forEach((k, v) -> d.put(String.valueOf(k), v)); return d;
        }
        return new Document();
    }
    private static List<Document> docs(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Document> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Document document) result.add(document);
        return result;
    }
    private static Instant instant(Object value) {
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value != null) try { return Instant.parse(String.valueOf(value)); } catch (Exception ignored) { }
        return null;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)); }
    private static long longNumber(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (Exception ignored) { return 0; }
    }
    private static int intNumber(Object value) { return (int) longNumber(value); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale); return Math.round(value * factor) / factor;
    }
}
