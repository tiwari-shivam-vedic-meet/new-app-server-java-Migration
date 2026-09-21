package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Consultant wallet/payout reads from consultant-service.js.
 *
 * <p>This deliberately keeps the Node money ladder (credit minus debit, then PG, then TDS)
 * and its response field names. It never mutates a wallet.</p>
 */
@Service
public class ConsultantWalletReadService {

    private static final ZoneOffset ZONE = ZoneOffset.UTC; // production Node host runs UTC
    private final MongoTemplate mongo;

    public ConsultantWalletReadService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Map<String, Object> wallet(Document actor, String filterBy, String userType) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        String type = blank(userType) ? "cons" : userType;
        Period period = period(blank(filterBy) ? "today" : filterBy);

        Document base = new Document("consultantId", consultantId)
                .append("userType", type).append("isConsTransfer", false);
        double lifetime = net(base);
        double weekly = net(new Document(base).append("createdAt",
                new Document("$gte", Date.from(Instant.now().minusSeconds(7L * 86_400L)))));
        Document dated = new Document(base).append("createdAt",
                new Document("$gte", period.start()).append("$lt", period.end()));
        double earning = net(dated);

        List<Document> ranking = mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", new Document("userType", "cons")
                        .append("isConsTransfer", false)
                        .append("createdAt", new Document("$gte", period.start()).append("$lt", period.end()))),
                new Document("$group", new Document("_id", "$consultantId")
                        .append("totalCredits", new Document("$sum", new Document("$cond", List.of(
                                new Document("$eq", List.of("$transactionType", 0)), coinAsDouble(), 0))))
                        .append("totalDebits", new Document("$sum", new Document("$cond", List.of(
                                new Document("$eq", List.of("$transactionType", 1)), coinAsDouble(), 0))))),
                new Document("$addFields", new Document("netEarnings",
                        new Document("$subtract", List.of("$totalCredits", "$totalDebits")))),
                new Document("$lookup", new Document("from", Collections.CONSULTANTS)
                        .append("localField", "_id").append("foreignField", "_id").append("as", "consultant")),
                new Document("$unwind", "$consultant"),
                new Document("$match", new Document("consultant.isActive", true)
                        .append("netEarnings", new Document("$gt", 0))),
                new Document("$sort", new Document("netEarnings", -1))
        )).into(new ArrayList<>());
        int position = 0;
        for (int i = 0; i < ranking.size(); i++) {
            if (consultantId.equals(ranking.get(i).get("_id"))) { position = i + 1; break; }
        }

        Rates rates = rates();
        double pg = money(earning * rates.pg() / 100d);
        double subtotal = money(earning - pg);
        double tds = money(subtotal * rates.tds() / 100d);
        double payable = money(subtotal - tds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("availableBalance", money(earning));
        result.put("payableBalance", payable);
        result.put("subtotal", subtotal);
        result.put("lifeTimeEarning", money(lifetime));
        result.put("weeklyEarning", money(weekly));
        result.put("myEarning", money(earning));
        result.put("myRanking", position);
        result.put("paymentGatewayCharge", formatMoney(pg)); // Node toFixed returns String
        result.put("tdsCharge", formatMoney(tds));
        result.put("gstCharge", 0);
        result.put("filterBy", period.name());
        return result;
    }

    public Map<String, Object> walletHistory(Document actor, String month) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        YearMonth now = YearMonth.now(ZONE);
        Date sevenMonthsAgo = Date.from(now.minusMonths(6).atDay(1).atStartOfDay().toInstant(ZONE));
        List<Document> transactions = mongo.getCollection(Collections.WALLET_TRANSACTIONS)
                .find(new Document("consultantId", consultantId).append("userType", "cons")
                        .append("isConsTransfer", false)
                        .append("createdAt", new Document("$gte", sevenMonthsAgo)))
                .into(new ArrayList<>());
        Rates rates = rates();

        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            YearMonth ym = now.minusMonths(i);
            LedgerBuckets bucket = classify(transactions, ym.atDay(1), ym.plusMonths(1).atDay(1));
            monthly.add(summary(ym.toString(), ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.US)
                    + " " + ym.getYear(), bucket, rates));
        }

        List<Map<String, Object>> daily = null;
        if (!blank(month)) {
            YearMonth selected;
            try { selected = YearMonth.parse(month); }
            catch (Exception invalid) { throw new IllegalArgumentException("INVALID_MONTH"); }
            Map<LocalDate, LedgerBuckets> byDay = new LinkedHashMap<>();
            for (Document transaction : transactions) {
                LocalDate day = date(transaction.get("createdAt"));
                if (day == null || !YearMonth.from(day).equals(selected)) continue;
                add(transaction, byDay.computeIfAbsent(day, ignored -> new LedgerBuckets()));
            }
            daily = byDay.entrySet().stream()
                    .sorted(Map.Entry.<LocalDate, LedgerBuckets>comparingByKey().reversed())
                    .map(entry -> summary(entry.getKey().toString(), null, entry.getValue(), rates))
                    .toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthlyData", monthly);
        result.put("dayWiseData", daily);
        return result;
    }

    public Map<String, Object> payoutHistory(Document actor) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        LocalDate cutoff = YearMonth.now(ZONE).minusMonths(5).atDay(1);
        List<Document> raw = mongo.getCollection(Collections.CONSULTANT_PAYOUTS)
                .find(new Document("consultantId", consultantId)).into(new ArrayList<>());
        List<Document> list = raw.stream()
                .filter(item -> {
                    LocalDate sortable = payoutDate(item);
                    return sortable != null && !sortable.isBefore(cutoff);
                })
                .sorted(Comparator.comparing(this::payoutDate).reversed())
                .toList();
        return Map.of("list", list);
    }

    private double net(Document filter) {
        Document result = mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", filter),
                new Document("$group", new Document("_id", null)
                        .append("credits", new Document("$sum", new Document("$cond", List.of(
                                new Document("$eq", List.of("$transactionType", 0)), coinAsDouble(), 0))))
                        .append("debits", new Document("$sum", new Document("$cond", List.of(
                                new Document("$eq", List.of("$transactionType", 1)), coinAsDouble(), 0)))))
        )).first();
        return result == null ? 0 : number(result.get("credits")) - number(result.get("debits"));
    }

    static LedgerBuckets classify(List<Document> transactions, LocalDate fromInclusive, LocalDate toExclusive) {
        LedgerBuckets result = new LedgerBuckets();
        for (Document transaction : transactions) {
            LocalDate day = date(transaction.get("createdAt"));
            if (day != null && !day.isBefore(fromInclusive) && day.isBefore(toExclusive)) add(transaction, result);
        }
        return result;
    }

    static void add(Document transaction, LedgerBuckets result) {
        double amount = number(transaction.get("coins"));
        int type = transaction.get("transactionType") instanceof Number n ? n.intValue() : -1;
        String transactionFor = text(transaction.get("transactionFor")).toLowerCase(Locale.ROOT);
        if (type == 0) {
            if ("wallet_refund".equals(transactionFor)) { result.walletRefund += amount; return; }
            String mode = text(doc(transaction.get("meta")).get("mode")).toLowerCase(Locale.ROOT);
            if ("session_book".equals(transactionFor)) result.fixedEarning += amount;
            else if ("live".equals(transactionFor)) result.callEarning += amount;
            else if ("consult".equals(transactionFor) && "chat".equals(mode)) result.chatEarning += amount;
            else if ("consult".equals(transactionFor)
                    && Set.of("video", "audio", "call", "voice").contains(mode)) result.callEarning += amount;
            else return;
            Object orderId = doc(transaction.get("meta")).get("orderId");
            if (orderId != null) result.orderIds.add(String.valueOf(orderId));
        } else if (type == 1 && !"payout".equals(transactionFor)) {
            result.walletDeduct += amount;
        }
    }

    private Map<String, Object> summary(String key, String label, LedgerBuckets b, Rates rates) {
        double total = b.chatEarning + b.callEarning + b.fixedEarning;
        double net = Math.max(0, total - b.walletDeduct + b.walletRefund);
        double pg = money(net * rates.pg() / 100d);
        double tds = money((net - pg) * rates.tds() / 100d);
        Map<String, Object> result = new LinkedHashMap<>();
        if (key.length() == 7) result.put("month", key); else result.put("date", key);
        if (label != null) result.put("monthLabel", label);
        result.put("ordersCount", b.orderIds.size());
        result.put("chatEarning", money(b.chatEarning));
        result.put("callEarning", money(b.callEarning));
        result.put("fixedEarning", money(b.fixedEarning));
        result.put("walletDeduct", money(b.walletDeduct));
        result.put("walletRefund", money(b.walletRefund));
        result.put("totalEarning", money(total));
        result.put("pgCharge", pg);
        result.put("tdsCharge", tds);
        result.put("finalPayable", money(net - pg - tds));
        return result;
    }

    private Rates rates() {
        Document master = mongo.getCollection(Collections.MASTERS).find().first();
        Document payment = doc(master == null ? null : master.get("payment"));
        return new Rates(orDefault(payment.get("PG"), 2.5), orDefault(payment.get("TDS"), 10));
    }

    static Period period(String requested) {
        String filter = requested.toLowerCase(Locale.ROOT);
        LocalDate now = LocalDate.now(ZONE);
        LocalDate start;
        LocalDate end;
        if ("yesterday".equals(filter)) { start = now.minusDays(1); end = now; }
        else if ("week".equals(filter) || "weekly".equals(filter)) {
            start = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); end = start.plusDays(7);
            filter = "week";
        } else {
            Map<String, Integer> months = Map.ofEntries(
                    Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
                    Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
                    Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("oct", 10), Map.entry("nov", 11),
                    Map.entry("dec", 12));
            Integer month = months.get(filter);
            if (month == null) { filter = "today"; start = now; end = now.plusDays(1); }
            else {
                int year = month > now.getMonthValue() ? now.getYear() - 1 : now.getYear();
                start = LocalDate.of(year, month, 1); end = start.plusMonths(1);
            }
        }
        return new Period(filter, Date.from(start.atStartOfDay().toInstant(ZONE)),
                Date.from(end.atStartOfDay().toInstant(ZONE)));
    }

    private LocalDate payoutDate(Document item) {
        Object value = item.get("payOutDate");
        Document settlement = doc(item.get("settlementPeriod"));
        if (value == null) value = settlement.get("endDate");
        if (value == null) value = settlement.get("startDate");
        return date(value);
    }

    private static Object coinAsDouble() {
        return new Document("$convert", new Document("input", "$coins").append("to", "double")
                .append("onError", 0).append("onNull", 0));
    }
    private static ObjectId id(Object value, String error) {
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException(error);
    }
    private static LocalDate date(Object value) {
        if (value instanceof Date date) return date.toInstant().atZone(ZONE).toLocalDate();
        if (value instanceof Instant instant) return instant.atZone(ZONE).toLocalDate();
        if (value instanceof String text) {
            try { return Instant.parse(text).atZone(ZONE).toLocalDate(); }
            catch (Exception ignored) {
                try { return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE); }
                catch (Exception ignoredAgain) { return null; }
            }
        }
        return null;
    }
    private static Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) {
            Document converted = new Document(); map.forEach((k, v) -> converted.put(String.valueOf(k), v)); return converted;
        }
        return new Document();
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return 0;
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }
    private static double orDefault(Object value, double fallback) {
        double parsed = number(value); return parsed == 0 ? fallback : parsed;
    }
    private static double money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    private static String formatMoney(double value) { return String.format(Locale.ROOT, "%.2f", money(value)); }

    record Period(String name, Date start, Date end) {}
    record Rates(double pg, double tds) {}
    static final class LedgerBuckets {
        final Set<String> orderIds = new LinkedHashSet<>();
        double chatEarning;
        double callEarning;
        double fixedEarning;
        double walletDeduct;
        double walletRefund;
    }
}
