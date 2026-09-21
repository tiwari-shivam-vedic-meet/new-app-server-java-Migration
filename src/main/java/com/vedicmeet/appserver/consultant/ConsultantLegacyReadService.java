package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compatibility reads for the active routes left at the bottom of
 * {@code rest-apis/modules/consultant/consultant.js}.
 *
 * <p>Two production Node routes are internally stale: live history references the removed
 * {@code waitlistBroadcast} registry alias and performance/tag calls an undefined method. Java
 * keeps the mobile URLs and response shapes, but reads the current {@code waitlists} and
 * {@code consultanttags} collections instead of reproducing those crashes.</p>
 */
@Service
public class ConsultantLegacyReadService {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private final MongoTemplate mongo;

    public ConsultantLegacyReadService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Document paySlip(Document actor, int monthNumber, int year) {
        if (monthNumber < 1 || monthNumber > 12 || year < 2000) {
            throw new IllegalArgumentException("INVALID_MONTH_OR_YEAR");
        }
        ObjectId consultantId = actorId(actor);
        String month = Month.of(monthNumber).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        Document snapshot = mongo.getCollection(Collections.CONSULTANT_PAYOUT_MONTH_ENDS)
                .find(new Document("month", month).append("year", String.valueOf(year))
                        .append("consWallet.consultantId", consultantId)).first();
        if (snapshot == null) return null;

        Document master = mongo.getCollection(Collections.MASTERS).find().first();
        Document payment = doc(master == null ? null : master.get("payment"));
        double payout = number(snapshot.get("coins"));
        String email = firstText(snapshot, "email");
        if (email.isBlank()) email = firstText(doc(actor.get("details")), "email");

        return new Document("MONTH", month)
                .append("NAME", snapshot.get("name"))
                .append("ADDRESS", actor.get("address"))
                .append("EMAIL", email)
                .append("MOBILE", mobile(snapshot.get("mobile")))
                .append("SAVING", round(payout / (1d - .025d - .10d), 2))
                .append("PG_CHARGE", valueOr(payment.get("PG"), 2.5d))
                .append("TDS_CHARGE", valueOr(payment.get("TDS"), 10d))
                .append("MONTHLY_PAYOUT_AMOUNT", payout)
                // Node uses number-to-words only in the email template. Keep the field stable even
                // when the optional mail transport is disabled.
                .append("PAID_AMOUNT_IN_WORD", String.format(Locale.US, "%.2f", payout))
                .append("PAN", snapshot.get("panNumber"))
                .append("BANK_NAME", snapshot.get("bankName"))
                .append("ACCOUNT_NUMBER", snapshot.get("accountNumber"))
                .append("TRANSACTION_ID", text(snapshot.get("_id")))
                .append("IFCS_CODE", snapshot.get("ifsc"))
                .append("ACCOUNT_HOLDER_NAME", snapshot.get("bankHolderName"))
                .append("PAYMENT_DATE", snapshot.get("createdAt"));
    }

    /** Intended version of Node /user/waitlist, scoped to the authenticated actor. */
    public Document userWaitlist(Document actor) {
        ObjectId userId = actorId(actor);
        Date tomorrow = Date.from(LocalDate.now(UTC).plusDays(1).atStartOfDay().toInstant(UTC));
        List<Document> waiting = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .find(new Document("userId", userId).append("isConsultantCompleted", "waiting")
                        .append("createdAt", new Document("$lt", tomorrow)))
                .projection(Projections.include("planType", "totalCallMinutes", "totalCallSeconds",
                        "userId", "typeOfConsult", "consultantId", "createdAt", "isEmergency"))
                .into(new ArrayList<>());
        if (waiting.isEmpty()) throw new IllegalArgumentException("RECORD_NOT_FOUND");

        Map<String, Integer> plans = Map.of("GOLD", 0, "SILVER", 1, "OTHERS", 2);
        waiting.sort(Comparator.comparingInt(row -> plans.getOrDefault(text(row.get("planType")), 3)));
        waiting.sort(Comparator.comparingInt((Document row) -> bool(row.get("isEmergency")) ? 1 : 0).reversed());
        int index = indexOf(waiting, userId);
        if (index < 0) throw new IllegalArgumentException("RECORD_NOT_FOUND");

        Document selected = new Document(waiting.get(index));
        Object consultantId = selected.get("consultantId");
        Document inProgress = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .find(new Document("consultantId", consultantId).append("isConsultantCompleted", "progress"))
                .first();
        long seconds = 0;
        if (inProgress != null) {
            seconds += Math.max(0, longNumber(inProgress.get("totalCallSeconds"))
                    - elapsedSeconds(inProgress.get("callStartTime")));
        }
        for (int i = 0; i < index; i++) seconds += longNumber(waiting.get(i).get("totalCallSeconds"));
        int status = index == 0 && inProgress == null ? 1 : index == 0 ? 2 : 3;
        Document detail = new Document("waitingTime", seconds).append("callDetail", selected);
        return new Document("status", status).append("data", detail).append("currentDate", new Date());
    }

    /** Current-schema replacement for Node's broken waitlistBroadcast live-history query. */
    public Map<String, Object> liveHistory(Document actor, int page, int limit, String search) {
        String consultantId = text(actorId(actor));
        int safeLimit = clamp(limit, 1, 100);
        int skip = Math.max(0, page - 1) * safeLimit;
        Document match = new Document("consultant_id", consultantId)
                .append("used_for", "live_event").append("status", "completed");
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", match));
        pipeline.add(lookup(Collections.CONSULTANTS, "consultant_id", "consultantDetail"));
        pipeline.add(unwind("consultantDetail"));
        pipeline.add(lookup(Collections.USERS, "user_id", "userDetail"));
        pipeline.add(unwind("userDetail"));
        if (search != null && !search.isBlank()) {
            Pattern value = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            pipeline.add(new Document("$match", new Document("$or", List.of(
                    new Document("userDetail.name", value), new Document("userDetail.userId", value),
                    new Document("consultantDetail.name", value), new Document("consultantDetail.userId", value)))));
        }
        pipeline.add(new Document("$facet", new Document("list", List.of(
                        new Document("$sort", new Document("createdAt", -1)),
                        new Document("$skip", skip), new Document("$limit", safeLimit)))
                .append("count", List.of(new Document("$count", "total")))));
        Document result = mongo.getCollection(Collections.WAITLISTS).aggregate(pipeline).first();
        List<Document> list = docs(result == null ? null : result.get("list"));
        List<Document> count = docs(result == null ? null : result.get("count"));
        return Map.of("list", list, "total", count.isEmpty() ? 0 : longNumber(count.get(0).get("total")));
    }

    public Map<String, Object> performanceFirst(Document actor, String requestedId) {
        ObjectId id = ownedConsultantId(actor, requestedId);
        String sid = id.toHexString();
        Date ninetyDays = Date.from(Instant.now().minus(Duration.ofDays(90)));
        Date sevenDays = Date.from(Instant.now().minus(Duration.ofDays(7)));

        Map<String, Object> ratings = ratingAverages(id, ninetyDays);
        Map<String, Object> talk = talkAverages(sid, ninetyDays);
        Map<String, Object> availability = availabilityRates(id, sevenDays);
        Map<String, Object> loyalty = loyalty(sid, ninetyDays);
        Map<String, Object> conversion = conversion(sid, ninetyDays);
        Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", id)).projection(Projections.include("price")).first();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consultantAvgRating", ratings);
        result.put("consultantAvgTalkTime", talk);
        result.put("availibilityRate", availability); // production spelling is contractual
        result.put("loyalCustomer", loyalty);
        result.put("newCustomerConversion", conversion);
        result.put("myCurrentPrice", consultant == null ? null : new Document("price", consultant.get("price")));
        return result;
    }

    public Map<String, Object> performanceSecond(Document actor, String requestedId) {
        ObjectId id = ownedConsultantId(actor, requestedId);
        String sid = id.toHexString();
        Date ninetyDays = Date.from(Instant.now().minus(Duration.ofDays(90)));
        Date sevenDays = Date.from(Instant.now().minus(Duration.ofDays(7)));
        Map<String, Object> averages = ratingAverages(id, ninetyDays);
        double average = average(number(averages.get("chatAvgRating")), number(averages.get("callAvgRating")));
        double served = servedProperly(sid, ninetyDays);
        double retention = retention(sid, ninetyDays, sevenDays);
        double conversion = number(conversion(sid, ninetyDays).get("custConversion"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("custSatisfaction", Map.of("avgRating", round(average, 1), "avgRatingRank", 0));
        result.put("newUserServeProperly", Map.of("serveProperlyPercentage", round(served, 2),
                "serveProperlyPercentageRank", 0));
        result.put("newCusAvgRating", Map.of("newCusAvgRating", round(average, 1), "newCusAvgRatingRank", 0));
        result.put("customerRetention", Map.of("customerRetention", round(retention, 2),
                "customerRetentionRank", 0));
        result.put("userRetention", Map.of("userRetention", round(conversion, 2), "userRetentionRank", 0));
        result.put("avgRatingCons", average == 0 ? new Document() : new Document("_id", null)
                .append("averageRating", round(average, 2)));
        return result;
    }

    public Map<String, Object> otherPerformance(Document actor, String requestedId) {
        ObjectId id = ownedConsultantId(actor, requestedId);
        String sid = id.toHexString();
        Date sevenDays = Date.from(Instant.now().minus(Duration.ofDays(7)));
        long followers = mongo.getCollection(Collections.FOLLOWS)
                .countDocuments(new Document("consultantId", id).append("status", true));

        List<Document> earnings = mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", new Document("consultantId", id).append("userType", "cons")),
                new Document("$group", new Document("_id", "$transactionFor")
                        .append("total", new Document("$sum", convertDouble("$coins")))))).into(new ArrayList<>());
        double consult = 0, live = 0;
        for (Document row : earnings) {
            if ("consult".equals(row.getString("_id"))) consult = number(row.get("total"));
            if ("live".equals(row.getString("_id"))) live = number(row.get("total"));
        }

        List<Document> weeklyRanks = mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", new Document("userType", "cons").append("transactionFor", "consult")
                        .append("createdAt", new Document("$gte", sevenDays))),
                new Document("$group", new Document("_id", "$consultantId")
                        .append("total", new Document("$sum", convertDouble("$coins")))),
                new Document("$sort", new Document("total", -1)))).into(new ArrayList<>());
        int rank = 0;
        for (int i = 0; i < weeklyRanks.size(); i++) {
            if (text(weeklyRanks.get(i).get("_id")).equals(sid)) { rank = i + 1; break; }
        }

        Map<String, Object> performance = Map.of("totalFollwer", followers,
                "totalEaring", round(consult, 2), "totalLiveEaring", round(live, 2), "rank", rank);
        return Map.of("otherPerformace", performance, "onlineTime", onlineAndBusy(id, sid, sevenDays));
    }

    /** Intended implementation for Node's undefined getPerformanceTag method. */
    public Map<String, Object> performanceTags(Document actor, String requestedId) {
        ObjectId id = ownedConsultantId(actor, requestedId);
        Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", id)).projection(Projections.include("tags")).first();
        List<ObjectId> ids = new ArrayList<>();
        if (consultant != null && consultant.get("tags") instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof ObjectId objectId) ids.add(objectId);
                else if (value != null && ObjectId.isValid(text(value))) ids.add(new ObjectId(text(value)));
            }
        }
        List<Document> assigned = ids.isEmpty() ? List.of() : mongo.getCollection(Collections.CONSULTANT_TAGS)
                .find(new Document("_id", new Document("$in", ids)).append("status", true))
                .sort(Sorts.ascending("name")).into(new ArrayList<>());
        return Map.of("consultantId", id, "tags", assigned);
    }

    private Map<String, Object> ratingAverages(ObjectId consultantId, Date since) {
        List<Document> rows = mongo.getCollection(Collections.REVIEW_AND_RATINGS).aggregate(List.of(
                new Document("$match", new Document("consultantId", consultantId).append("status", true)
                        .append("createdAt", new Document("$gte", since))
                        .append("ratingType", new Document("$in", List.of("chat", "call")))),
                new Document("$group", new Document("_id", "$ratingType")
                        .append("average", new Document("$avg", "$rating"))))).into(new ArrayList<>());
        double chat = 0, call = 0;
        for (Document row : rows) {
            if ("chat".equals(row.getString("_id"))) chat = number(row.get("average"));
            if ("call".equals(row.getString("_id"))) call = number(row.get("average"));
        }
        return Map.of("chatAvgRating", round(chat, 1), "callAvgRating", round(call, 1),
                "chatAvgRatingRank", 0, "callAvgRatingRank", 0);
    }

    private Map<String, Object> talkAverages(String consultantId, Date since) {
        List<Document> rows = mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("consultant_id", consultantId).append("status", "completed")
                        .append("createdAt", new Document("$gte", since))),
                new Document("$group", new Document("_id", "$session_info.mode")
                        .append("seconds", new Document("$avg", new Document("$ifNull",
                                List.of("$onCompletion.callDurationInSeconds", 0))))))).into(new ArrayList<>());
        double chat = 0, call = 0;
        for (Document row : rows) {
            if ("chat".equals(row.getString("_id"))) chat = number(row.get("seconds")) / 60d;
            if (Set.of("audio", "video").contains(row.getString("_id"))) call = number(row.get("seconds")) / 60d;
        }
        return Map.of("avgCallMinutes", round(call, 2), "avgChatMinutes", round(chat, 2),
                "avgCallMinutesRank", 0, "avgChatMinutesRank", 0);
    }

    private Map<String, Object> availabilityRates(ObjectId consultantId, Date since) {
        double chat = 0, call = 0, total = 0;
        for (Document row : mongo.getCollection(Collections.ONLINE_RECORDS)
                .find(new Document("consultantId", consultantId).append("startTime", new Document("$gte", since)))) {
            double minutes = durationMinutes(row.get("startTime"), row.get("endTime"));
            total += minutes;
            String type = text(row.get("type")).toUpperCase(Locale.ROOT);
            if ("CHAT".equals(type)) chat += minutes;
            if ("CALL".equals(type) || "VIDEO".equals(type)) call += minutes;
        }
        return Map.of("chatAvailibilityRate", round(chat / 7d / 60d, 2),
                "callAvailibilityRate", round(call / 7d / 60d, 2),
                "onlineTime", round(total / 7d / 60d, 2));
    }

    private Map<String, Object> loyalty(String consultantId, Date since) {
        List<Document> rows = sessionCounts(consultantId, since);
        long repeat = rows.stream().filter(row -> longNumber(row.get("count")) > 1).count();
        double value = rows.isEmpty() ? 0 : repeat * 100d / rows.size();
        return Map.of("loyalCus", round(value, 2), "loyalCusRank", 0);
    }

    private Map<String, Object> conversion(String consultantId, Date since) {
        List<Document> rows = sessionCounts(consultantId, since);
        long converted = rows.stream().filter(row -> longNumber(row.get("count")) > 1).count();
        double value = rows.isEmpty() ? 0 : converted * 100d / rows.size();
        return Map.of("custConversion", round(value, 2), "custConversionRank", 0);
    }

    private List<Document> sessionCounts(String consultantId, Date since) {
        return mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("consultant_id", consultantId).append("status", "completed")
                        .append("createdAt", new Document("$gte", since))),
                new Document("$group", new Document("_id", "$user_id")
                        .append("count", new Document("$sum", 1))))).into(new ArrayList<>());
    }

    private double servedProperly(String consultantId, Date since) {
        Document match = new Document("consultant_id", consultantId).append("status", "completed")
                .append("createdAt", new Document("$gte", since));
        Document lookup = new Document("from", Collections.REVIEW_AND_RATINGS)
                .append("localField", "_id").append("foreignField", "consultantRequestFormId")
                .append("as", "reviews");
        Document reviewFilter = new Document("input", "$reviews").append("as", "review")
                .append("cond", new Document("$gte", List.of("$$review.rating", 4)));
        Document good = new Document("$gt", List.of(
                new Document("$size", new Document("$filter", reviewFilter)), 0));
        Document group = new Document("_id", null).append("total", new Document("$sum", 1))
                .append("good", new Document("$sum", new Document("$cond", List.of("$good", 1, 0))));
        List<Document> rows = mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", match), new Document("$lookup", lookup),
                new Document("$project", new Document("good", good)), new Document("$group", group)
        )).into(new ArrayList<>());
        if (rows.isEmpty() || number(rows.get(0).get("total")) == 0) return 0;
        return number(rows.get(0).get("good")) * 100d / number(rows.get(0).get("total"));
    }

    private double retention(String consultantId, Date ninetyDays, Date sevenDays) {
        List<Document> all = sessionCounts(consultantId, ninetyDays);
        if (all.isEmpty()) return 0;
        List<Document> recent = sessionCounts(consultantId, sevenDays);
        Set<String> recentIds = recent.stream().map(row -> text(row.get("_id"))).collect(java.util.stream.Collectors.toSet());
        long retained = all.stream().filter(row -> recentIds.contains(text(row.get("_id")))).count();
        return retained * 100d / all.size();
    }

    private Map<String, Object> onlineAndBusy(ObjectId consultantId, String sid, Date since) {
        Map<String, Double> online = new LinkedHashMap<>();
        online.put("CHAT", 0d); online.put("CALL", 0d); online.put("VIDEO", 0d);
        for (Document row : mongo.getCollection(Collections.ONLINE_RECORDS)
                .find(new Document("consultantId", consultantId).append("startTime", new Document("$gte", since)))) {
            String type = text(row.get("type")).toUpperCase(Locale.ROOT);
            if (online.containsKey(type)) online.merge(type, durationMinutes(row.get("startTime"), row.get("endTime")), Double::sum);
        }
        Map<String, Double> busy = new LinkedHashMap<>();
        busy.put("chat", 0d); busy.put("audio", 0d); busy.put("video", 0d);
        for (Document row : mongo.getCollection(Collections.WAITLISTS).find(
                new Document("consultant_id", sid).append("status", "completed")
                        .append("createdAt", new Document("$gte", since)))) {
            String mode = text(doc(row.get("session_info")).get("mode"));
            if (busy.containsKey(mode)) busy.merge(mode,
                    number(doc(row.get("onCompletion")).get("callDurationInSeconds")) / 60d, Double::sum);
        }
        return Map.of(
                "chat", Map.of("onlineTime", round(online.get("CHAT"), 2), "busyTime", round(busy.get("chat"), 2)),
                "call", Map.of("onlineTime", round(online.get("CALL"), 2), "busyTime", round(busy.get("audio"), 2)),
                "videoCall", Map.of("onlineTime", round(online.get("VIDEO"), 2), "busyTime", round(busy.get("video"), 2)),
                "liveEvent", Map.of("onlineTime", 0, "busyTime", 0));
    }

    private ObjectId ownedConsultantId(Document actor, String requestedId) {
        ObjectId actual = actorId(actor);
        if (requestedId != null && !requestedId.isBlank() && !actual.toHexString().equals(requestedId)) {
            throw new IllegalArgumentException("CONSULTANT_ID_MISMATCH");
        }
        return actual;
    }

    private ObjectId actorId(Document actor) {
        Object value = actor == null ? null : actor.get("_id");
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(text(value))) return new ObjectId(text(value));
        throw new IllegalStateException("Consultant not found");
    }
    private int indexOf(List<Document> rows, ObjectId actor) {
        for (int i = 0; i < rows.size(); i++) if (text(rows.get(i).get("userId")).equals(actor.toHexString())) return i;
        return -1;
    }
    private long elapsedSeconds(Object value) {
        Instant start = instant(value);
        return start == null ? 0 : Math.max(0, Duration.between(start, Instant.now()).toSeconds());
    }
    private double durationMinutes(Object start, Object end) {
        Instant from = instant(start);
        Instant to = instant(end);
        if (from == null) return 0;
        if (to == null) to = Instant.now();
        return Math.max(0, Duration.between(from, to).toSeconds()) / 60d;
    }
    private Instant instant(Object value) {
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value != null) try { return Instant.parse(text(value)); } catch (RuntimeException ignored) { }
        return null;
    }
    private Document lookup(String from, String local, String as) {
        // IDs are strings in current waitlists and ObjectIds in account collections.
        return new Document("$lookup", new Document("from", from)
                .append("let", new Document("id", "$" + local))
                .append("pipeline", List.of(new Document("$match", new Document("$expr",
                        new Document("$eq", List.of(new Document("$toString", "$_id"), "$$id"))))))
                .append("as", as));
    }
    private Document unwind(String field) {
        return new Document("$unwind", new Document("path", "$" + field).append("preserveNullAndEmptyArrays", true));
    }
    private Document convertDouble(String field) {
        return new Document("$convert", new Document("input", field).append("to", "double")
                .append("onError", 0).append("onNull", 0));
    }
    private List<Document> docs(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Document> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Document document) result.add(document);
        return result;
    }
    @SuppressWarnings("unchecked")
    private Document doc(Object value) {
        if (value instanceof Document document) return document;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }
    private String firstText(Document source, String key) { return source == null ? "" : text(source.get(key)); }
    private String mobile(Object value) { return value == null || text(value).isBlank() ? "" : "+91 " + text(value); }
    private Object valueOr(Object value, Object fallback) { return value == null ? fallback : value; }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private long longNumber(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); } catch (RuntimeException ignored) { return 0; }
    }
    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (RuntimeException ignored) { return 0; }
    }
    private double average(double a, double b) { return a == 0 ? b : b == 0 ? a : (a + b) / 2d; }
    private double round(double value, int scale) {
        if (!Double.isFinite(value)) return 0;
        double factor = Math.pow(10, scale); return Math.round(value * factor) / factor;
    }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
