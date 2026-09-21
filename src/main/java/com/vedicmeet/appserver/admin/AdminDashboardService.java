package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Faithful Java port of Node utils/classes/dashboard.js. */
@Service
public class AdminDashboardService {
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_SHORT_DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final String FREE_CHAT_COUPON_CODE = "FREE5MINUTES"; // FREE_CHAT_COUPON_CODE from utils/_constant.js

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminDashboardService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> getDetails() {
        long totalUser = mongo.getCollection(Collections.USERS).countDocuments();
        long totalConsultant = mongo.getCollection(Collections.CONSULTANTS).countDocuments();
        List<Document> totalEarningRows = aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(
                d("{ '$group': { '_id': null, 'platformEarning': { '$sum': { '$cond': [ { '$and': [ { '$or': [ { '$eq': ['$transactionFor', 'membership'] }, { '$eq': ['$transactionFor', 'consult'] }, { '$eq': ['$transactionFor', 'meditation'] }, { '$eq': ['$transactionFor', 'vastu'] }, { '$eq': ['$transactionFor', 'shopify'] } ] }, { '$eq': ['$transactionType', 1] } ] }, '$totalAmountPayToPlateform', 0 ] } } }, 'refundAmount': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$transactionFor', 'refund'] }, { '$eq': ['$userType', 'cons'] } ] }, { '$toDouble': '$coins' }, 0 ] } }, 'consultantTotalEarning': { '$sum': { '$cond': [ { '$and': [ { '$or': [ { '$eq': ['$transactionFor', 'membership'] }, { '$eq': ['$transactionFor', 'consult'] }, { '$eq': ['$transactionFor', 'gift'] }, { '$eq': ['$transactionFor', 'shopify'] } ] }, { '$eq': ['$transactionType', 0] }, { '$eq': ['$userType', 'cons'] } ] }, { '$toDouble': '$coins' }, 0 ] } } } }"),
                d("{ '$project': { '_id': 0, 'refundAmount': 1, 'platformEarning': { '$subtract': ['$platformEarning', '$refundAmount'] }, 'consultantTotalEarning': { '$subtract': ['$consultantTotalEarning', '$refundAmount'] } } }")));
        Date today = Date.from(LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneOffset.UTC).toInstant());
        Document goldRange = new Document("startDate", new Document("$lte", today)).append("endDate", new Document("$gte", today));
        List<Document> goldMemeberRows = aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(
                d("{ '$match': { 'transactionFor': 'membership' } }"),
                new Document("$facet", new Document("gold", Arrays.asList(d("{ '$match': { 'planType': 'GOLD' } }"), d("{ '$group': { '_id': '$userId', 'startDate': { '$last': '$startDate' }, 'endDate': { '$last': '$endDate' } } }"), d("{ '$project': { '_id': 0, 'startDate': 1, 'endDate': 1 } }"), new Document("$match", goldRange), d("{ '$count': 'total' }")))
                        .append("silver", Arrays.asList(d("{ '$match': { 'planType': 'SILVER' } }"), d("{ '$group': { '_id': '$userId', 'startDate': { '$last': '$startDate' }, 'endDate': { '$last': '$endDate' } } }"), d("{ '$project': { '_id': 0, 'startDate': 1, 'endDate': 1 } }"), new Document("$match", goldRange), d("{ '$count': 'total' }")))),
                d("{ '$unwind': '$gold' }"), d("{ '$unwind': '$silver' }")));
        long totalCalls = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).countDocuments(new Document("typeOfConsult", "audio").append("isConsultantCompleted", "complete"));
        long totalChats = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).countDocuments(new Document("typeOfConsult", "chat").append("isConsultantCompleted", "complete"));
        long totalVideo = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).countDocuments(new Document("typeOfConsult", "video").append("isConsultantCompleted", "complete"));
        long totalLiveConsultant = mongo.getCollection(Collections.BROADCASTS).countDocuments(new Document("status", 1));
        Document isFreeTrailOffer = mongo.getCollection(Collections.ADMINS).find(new Document("role", "admin")).projection(new Document("isFreeTrailOffer", 1)).first();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalUser", totalUser); r.put("totalConsultant", totalConsultant);
        r.put("totalEarning", totalEarningRows.isEmpty() ? totalEarningRows : totalEarningRows.get(0));
        r.put("goldMemeber", goldMemeberRows.isEmpty() ? goldMemeberRows : goldMemeberRows.get(0));
        r.put("totalCalls", totalCalls); r.put("totalChats", totalChats); r.put("totalVideo", totalVideo); r.put("totalLiveConsultant", totalLiveConsultant);
        r.put("freeTrailOffer", isFreeTrailOffer == null ? false : first(isFreeTrailOffer.get("isFreeTrailOffer"), false));
        return r;
    }

    public List<Document> getGraph(Map<String, String> query) {
        Date startDate = parseJavaScriptDateOrNow(v(query, "startDate"));
        Date endDate = endOfUtcDay(parseJavaScriptDateOrNow(v(query, "endDate")));
        Document params = new Document("$expr", new Document("$and", Arrays.asList(new Document("$gte", Arrays.asList("$createdAt", startDate)), new Document("$lt", Arrays.asList("$createdAt", endDate)))));
        return aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(new Document("$match", params), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt' } }, 'totalCount': { '$sum': 1 }, 'totalAmountPayToPlateform': { '$sum': { '$toDouble': '$totalAmountPayToPlateform' } } } }")));
    }

    public Map<String, Object> enableFreeTrail() {
        // FAITHFUL(node-quirk): rest-apis/modules/admin/dashboard.js free_trail calls enableFreeTrail() with no args.
        // Intended Node body: admins.findOneAndUpdate({role:"admin"}, {$set:{isFreeTrailOffer: <status>}}, {new:true})
        throw new IllegalStateException("Cannot read properties of undefined (reading 'status')");
    }

    public Map<String, Object> getQuickStats(Map<String, String> query) {
        Document dateFilter = quickStatsDateFilter(query == null ? Map.of() : query);
        long totalSignup = mongo.getCollection(Collections.USERS).countDocuments(new Document(dateFilter).append("isDeleted", false));
        long totalSignout = mongo.getCollection(Collections.USERS).countDocuments(new Document(dateFilter).append("isDeleted", true));
        List<Document> consultationStats = aggregate(Collections.WAITLISTS, Arrays.asList(new Document("$match", new Document(dateFilter)), d("{ '$group': { '_id': null, 'totalFreeConsultation': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$eq': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } }, 'totalPaidConsultation': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$ne': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } } } }")));
        List<Document> transactions = aggregate(Collections.TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("status", "COMPLETED")), d("{ '$group': { '_id': null, 'totalRecharge': { '$sum': { '$cond': { 'if': { '$eq': ['$status', 'COMPLETED'] }, 'then': '$paidAmount', 'else': 0 } } } } }")));
        List<Document> walletStats = aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("userType", "user").append("transactionFor", new Document("$in", Arrays.asList("consult", "gift", "meditation", "vastu", "session_book", "refund", "wallet_refund"))).append("coins", new Document("$ne", 0))), d("{ '$group': { '_id': null, 'totalSpending': { '$sum': 1 }, 'totalDebits': { '$sum': { '$cond': { 'if': { '$eq': ['$transactionType', 1] }, 'then': '$coins', 'else': 0 } } }, 'totalCredits': { '$sum': { '$cond': { 'if': { '$eq': ['$transactionType', 0] }, 'then': '$coins', 'else': 0 } } } } }")));
        List<Document> walletBalanceStats = aggregate(Collections.USERS, Arrays.asList(d("{ '$match': { 'isDeleted': false } }"), d("{ '$group': { '_id': '$userId', 'currentBalance': { '$sum': { '$toDouble': '$wallet' } } } }"), d("{ '$group': { '_id': null, 'totalWalletBalance': { '$sum': '$currentBalance' } } }")));
        Document c = consultationStats.isEmpty() ? new Document("totalFreeConsultation", 0).append("totalPaidConsultation", 0) : consultationStats.get(0);
        Document t = transactions.isEmpty() ? new Document("totalRecharge", 0) : transactions.get(0);
        Document b = walletBalanceStats.isEmpty() ? new Document("totalWalletBalance", 0) : walletBalanceStats.get(0);
        double amount = walletStats.isEmpty() ? 0 : number(walletStats.get(0).get("totalDebits")) - number(walletStats.get(0).get("totalCredits"));
        Map<String, Object> r = new LinkedHashMap<>();
        // FAITHFUL(node-quirk): Node route /quick-stats reshapes service data and appends timestamp; controller cannot, so this service returns that route result.
        r.put("totalSignup", totalSignup); r.put("totalSignout", totalSignout); r.put("totalFreeConsultation", first(c.get("totalFreeConsultation"), 0)); r.put("totalPaidConsultation", first(c.get("totalPaidConsultation"), 0)); r.put("totalRecharge", first(t.get("totalRecharge"), 0)); r.put("totalSpending", amount > 0 ? fixed(amount, 2) : 0); r.put("totalWalletBalance", number(b.get("totalWalletBalance")) > 0 ? fixed(number(b.get("totalWalletBalance")), 2) : 0); r.put("timestamp", new Date());
        return r;
    }

    public Map<String, Object> getLast10DaysData(Map<String, String> query) {
        Document dateFilter = last10DaysStyleDateFilter(query == null ? Map.of() : query);
        List<Document> daily = aggregate(Collections.WAITLISTS, Arrays.asList(new Document("$match", new Document(dateFilter)), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'date': { '$first': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } } }, 'consultationAttempt': { '$sum': 1 }, 'failedConsultation': { '$sum': { '$cond': [ { '$or': [ { '$eq': ['$status', 'canceled'] }, { '$eq': ['$status', 'missed'] } ] }, 1, 0 ] } }, 'freeConsultation': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$eq': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } }, 'paidConsultation': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$ne': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } } } }"), d("{ '$sort': { 'date': -1 } }")));
        List<Document> signups = aggregate(Collections.USERS, Arrays.asList(new Document("$match", new Document(dateFilter).append("isDeleted", new Document("$ne", true))), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'totalSignup': { '$sum': 1 } } }")));
        List<Document> signouts = aggregate(Collections.USERS, Arrays.asList(new Document("$match", new Document(dateFilter).append("isDeleted", true)), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'totalSignout': { '$sum': 1 } } }")));
        List<Document> recharge = aggregate(Collections.TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("status", "COMPLETED")), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'totalRecharge': { '$sum': { '$cond': { 'if': { '$eq': ['$status', 'COMPLETED'] }, 'then': '$paidAmount', 'else': 0 } } } } }")));
        List<Document> spending = aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("userType", "user").append("transactionType", 1).append("transactionFor", new Document("$in", Arrays.asList("consult", "gift", "meditation", "vastu", "session_book", "refund", "wallet_refund"))).append("coins", new Document("$ne", 0))), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'totalSpending': { '$sum': { '$ifNull': ['$coins', 0] } } } }")));
        List<Document> repeat = aggregate(Collections.WAITLISTS, Arrays.asList(new Document("$match", new Document(dateFilter).append("status", "completed")), d("{ '$group': { '_id': { 'date': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'userId': '$user_id' }, 'consultationCount': { '$sum': 1 } } }"), d("{ '$match': { 'consultationCount': { '$gt': 1 } } }"), d("{ '$group': { '_id': '$_id.date', 'repeatConsultation': { '$sum': 1 } } }")));
        Map<String, Object> signupMap = valuesById(signups, "totalSignup"), signoutMap = valuesById(signouts, "totalSignout"), rechargeMap = valuesById(recharge, "totalRecharge"), spendingMap = valuesById(spending, "totalSpending"), repeatMap = valuesById(repeat, "repeatConsultation");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String day : last10DaysReversed()) {
            Document e = findBy(daily, "date", day);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day); row.put("totalSignup", first(signupMap.get(day), 0)); row.put("totalSignout", first(signoutMap.get(day), 0)); row.put("consultationAttempt", e == null ? 0 : first(e.get("consultationAttempt"), 0)); row.put("failedConsultation", e == null ? 0 : first(e.get("failedConsultation"), 0)); row.put("freeConsultation", e == null ? 0 : first(e.get("freeConsultation"), 0)); row.put("paidConsultation", e == null ? 0 : first(e.get("paidConsultation"), 0)); row.put("repeatConsultation", first(repeatMap.get(day), 0)); row.put("totalRecharge", first(rechargeMap.get(day), 0)); row.put("totalSpending", first(spendingMap.get(day), 0));
            rows.add(row);
        }
        return new LinkedHashMap<>(Map.of("dailyBreakdown", rows));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDateWiseDataPercentageUserManagement(Map<String, String> query) {
        ZonedDateTime target = selectedTargetDate(query == null ? Map.of() : query);
        Date start = Date.from(target.toLocalDate().atStartOfDay(KOLKATA).toInstant());
        Date end = Date.from(target.toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA).toInstant());
        List<Document> users = mongo.getCollection(Collections.USERS).find(new Document("createdAt", new Document("$gte", start).append("$lte", end))).projection(new Document("_id", 1).append("isDeleted", 1)).into(new ArrayList<>());
        int totalSignup = users.size(), totalSignout = 0;
        for (Document u : users) if (Boolean.TRUE.equals(u.get("isDeleted"))) totalSignout++;
        Document lookup = d("{ '$lookup': { 'from': '" + Collections.USERS + "', 'let': { 'uid': '$userObjectId' }, 'pipeline': [ { '$match': { '$expr': { '$eq': ['$_id', '$$uid'] }, 'createdAt': {}, 'isDeleted': false } } ], 'as': 'user' } }");
        ((List<Document>) ((Document) lookup.get("$lookup")).get("pipeline")).get(0).get("$match", Document.class).put("createdAt", new Document("$gte", start).append("$lt", end));
        Document totalLookup = d("{ '$lookup': { 'from': '" + Collections.USERS + "', 'pipeline': [ { '$match': { 'createdAt': {}, 'isDeleted': false } }, { '$count': 'total' } ], 'as': 'totalUsersCount' } }");
        ((List<Document>) ((Document) totalLookup.get("$lookup")).get("pipeline")).get(0).get("$match", Document.class).put("createdAt", new Document("$gte", start).append("$lt", end));
        List<Document> data = aggregate(Collections.WAITLISTS, Arrays.asList(d("{ '$match': { 'user_id': { '$exists': true } } }"), d("{ '$group': { '_id': '$user_id', 'totalAttempts': { '$sum': 1 }, 'totalCompleted': { '$sum': { '$cond': [ { '$eq': ['$status', 'completed'] }, 1, 0 ] } } } }"), d("{ '$addFields': { 'userObjectId': { '$toObjectId': '$_id' } } }"), lookup, d("{ '$match': { 'user': { '$ne': [] } } }"), d("{ '$group': { '_id': null, 'usersWithAttempts': { '$sum': 1 }, 'usersWithCompleted': { '$sum': { '$cond': [ { '$gt': ['$totalCompleted', 0] }, 1, 0 ] } }, 'totalCompletedAttempts': { '$sum': '$totalCompleted' } } }"), totalLookup, d("{ '$project': { '_id': 0, 'totalUsers': { '$arrayElemAt': ['$totalUsersCount.total', 0] }, 'hasAttempt': '$usersWithAttempts', 'hasNotAttempt': { '$subtract': [ { '$arrayElemAt': ['$totalUsersCount.total', 0] }, '$usersWithAttempts' ] }, 'hasCompleted': '$usersWithCompleted', 'hasNotCompletedAttempt': { '$subtract': ['$usersWithAttempts', '$usersWithCompleted'] }, 'totalCompleted': '$totalCompletedAttempts' } }")));
        Document s = data.isEmpty() ? new Document("totalUsers", 0).append("hasAttempt", 0).append("hasNotAttempt", 0).append("hasCompleted", 0).append("hasNotCompletedAttempt", 0).append("totalCompleted", 0) : data.get(0);
        double totalDownloads = firstTruthyNumber(s.get("totalUsers"), totalSignup), consulted = firstTruthyNumber(s.get("hasAttempt"), 0), responded = firstTruthyNumber(s.get("hasCompleted"), 0), notResponded = firstTruthyNumber(s.get("hasNotCompletedAttempt"), 0), consultantResponded = firstTruthyNumber(s.get("totalCompleted"), 0), didNotTry = firstTruthyNumber(s.get("hasNotAttempt"), 0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("date", target.format(DISPLAY_DAY)); r.put("dateStr", target.format(ISO_DAY)); r.put("totalDownloads", jsNumber(totalDownloads)); r.put("consulted", jsNumber(consulted)); r.put("uniqueUsersResponded", jsNumber(responded)); r.put("consultantNotResponded", jsNumber(notResponded)); r.put("consultantResponded", jsNumber(consultantResponded)); r.put("didNotTryCount", jsNumber(didNotTry)); r.put("freeChatConsulted", 0); r.put("freeChatPercentage", 0); r.put("triedButNotRespondedPercentage", totalDownloads > 0 ? round((notResponded / totalDownloads) * 100, 1) : 0); r.put("didNotTryPercentage", totalDownloads > 0 ? round((didNotTry / totalDownloads) * 100, 1) : 0); r.put("uninstall", totalSignout); r.put("uninstallRate", totalDownloads > 0 ? round((totalSignout / totalDownloads) * 100, 2) : 0);
        return r;
    }

    public Map<String, Object> getUserAnalytics(Map<String, String> query) {
        Document dateFilter = last10DaysStyleDateFilter(query == null ? Map.of() : query);
        List<Document> growth = aggregate(Collections.USERS, Arrays.asList(new Document("$match", new Document(dateFilter)), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'dates': { '$first': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } } }, 'signups': { '$sum': { '$cond': [ { '$eq': ['$isDeleted', false] }, 1, 0 ] } }, 'activeUsers': { '$sum': { '$cond': [ { '$eq': ['$isDeleted', true] }, 1, 0 ] } }, 'totalUsers': { '$sum': 1 } } }"), d("{ '$sort': { 'dates': 1 } }")));
        List<Document> consultantsRaw = aggregate(Collections.WAITLISTS, Arrays.asList(new Document("$match", new Document(dateFilter).append("status", "completed")), d("{ '$lookup': { 'from': '" + Collections.CONSULTANTS + "', 'let': { 'consultantId': '$consultant_id' }, 'pipeline': [ { '$match': { '$expr': { '$eq': [ { '$toObjectId': '$$consultantId' }, '$_id' ] } } } ], 'as': 'consultant' } }"), d("{ '$unwind': { 'path': '$consultant', 'preserveNullAndEmptyArrays': true } }"), d("{ '$group': { '_id': '$consultant_id', 'name': { '$first': '$consultant.accountName' }, 'value': { '$sum': 1 }, 'sessions': { '$sum': 1 }, 'paidSessions': { '$sum': { '$cond': [ { '$ne': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] }, 1, 0 ] } }, 'freeSessions': { '$sum': { '$cond': [ { '$eq': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] }, 1, 0 ] } } } }"), d("{ '$group': { '_id': null, 'totalSessions': { '$sum': '$sessions' }, 'consultants': { '$push': '$$ROOT' } } }"), d("{ '$unwind': '$consultants' }"), d("{ '$addFields': { 'consultants.percentage': { '$round': [ { '$multiply': [ { '$divide': ['$consultants.sessions', '$totalSessions'] }, 100 ] }, 2 ] } } }"), d("{ '$replaceRoot': { 'newRoot': '$consultants' } }"), d("{ '$sort': { 'sessions': -1 } }"), d("{ '$limit': 5 }")));
        List<Document> dist = aggregate(Collections.WAITLISTS, Arrays.asList(new Document("$match", new Document(dateFilter)), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'dates': { '$first': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } } }, 'freeConsultations': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$eq': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } }, 'paidConsultations': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$ne': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } }, 'failedConsultations': { '$sum': { '$cond': [ { '$or': [ { '$eq': ['$status', 'canceled'] }, { '$eq': ['$status', 'missed'] } ] }, 1, 0 ] } }, 'repeatConsultations': { '$sum': { '$cond': [ { '$and': [ { '$ne': ['$user_id', null] }, { '$eq': ['$status', 'completed'] } ] }, 1, 0 ] } } } }"), d("{ '$sort': { 'dates': -1 } }")));
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> userGrowth = new LinkedHashMap<>(); userGrowth.put("dates", fieldList(growth, "dates")); userGrowth.put("signups", fieldList(growth, "signups")); userGrowth.put("activeUsers", fieldList(growth, "activeUsers")); userGrowth.put("totalUsers", fieldList(growth, "totalUsers")); r.put("userGrowth", userGrowth);
        List<Map<String, Object>> consultants = new ArrayList<>();
        for (Document i : consultantsRaw) { Map<String, Object> row = new LinkedHashMap<>(); row.put("name", truthy(i.get("name")) ? i.get("name") : "Unknown Consultant"); row.put("value", first(i.get("value"), 0)); row.put("sessions", first(i.get("sessions"), 0)); row.put("percentage", first(i.get("percentage"), 0)); row.put("paidSessions", first(i.get("paidSessions"), 0)); row.put("freeSessions", first(i.get("freeSessions"), 0)); consultants.add(row); }
        r.put("fiveConsultants", new LinkedHashMap<>(Map.of("consultantData", consultants)));
        Map<String, Object> monthly = new LinkedHashMap<>(); monthly.put("dates", fieldList(dist, "dates")); monthly.put("freeConsultations", fieldList(dist, "freeConsultations")); monthly.put("paidConsultations", fieldList(dist, "paidConsultations")); monthly.put("failedConsultations", fieldList(dist, "failedConsultations")); monthly.put("repeatConsultations", fieldList(dist, "repeatConsultations")); r.put("consultationDistributionMonthly", monthly);
        return r;
    }

    public Map<String, Object> getRevenueAnalytics(Map<String, String> query) {
        Document dateFilter = last10DaysStyleDateFilter(query == null ? Map.of() : query);
        List<Document> revenue = aggregate(Collections.TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("status", "COMPLETED")), d("{ '$group': { '_id': { 'year': { '$year': '$createdAt' }, 'month': { '$month': '$createdAt' }, 'day': { '$dayOfMonth': '$createdAt' } }, 'totalRevenue': { '$sum': { '$cond': { 'if': { '$eq': ['$status', 'COMPLETED'] }, 'then': { '$ifNull': ['$paidAmount', 0] }, 'else': 0 } } }, 'count': { '$sum': 1 } } }"), d("{ '$sort': { '_id.year': 1, '_id.month': 1, '_id.day': 1 } }")));
        List<Document> spending = aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("userType", "user").append("transactionType", 1).append("transactionFor", new Document("$in", Arrays.asList("consult", "gift", "meditation", "vastu", "session_book", "refund", "wallet_refund"))).append("coins", new Document("$ne", 0))), d("{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'totalSpending': { '$sum': { '$ifNull': ['$coins', 0] } }, 'count': { '$sum': 1 } } }"), d("{ '$sort': { '_id': 1 } }")));
        List<Document> byServiceRaw = aggregate(Collections.WALLET_TRANSACTIONS, Arrays.asList(new Document("$match", new Document(dateFilter).append("transactionType", 1).append("$or", Arrays.asList(new Document("transactionFor", "membership"), new Document("transactionFor", "consult"), new Document("transactionFor", "meditation"), new Document("transactionFor", "vastu"), new Document("transactionFor", "shopify")))), d("{ '$group': { '_id': '$transactionFor', 'totalRevenue': { '$sum': { '$toDouble': '$totalAmountPayToPlateform' } }, 'count': { '$sum': 1 } } }")));
        Map<String, Object> revenueMap = new LinkedHashMap<>();
        for (Document i : revenue) { Document id = i.get("_id", Document.class); if (id != null) revenueMap.put(String.format(Locale.US, "%d-%02d-%02d", ((Number) id.get("year")).intValue(), ((Number) id.get("month")).intValue(), ((Number) id.get("day")).intValue()), first(i.get("totalRevenue"), 0)); }
        Map<String, Object> spendingMap = valuesById(spending, "totalSpending");
        Document range = dateFilter.get("createdAt", Document.class);
        List<Object> revenueData = new ArrayList<>(), spendingData = new ArrayList<>(); List<String> categories = new ArrayList<>();
        for (Date date : datesBetween((Date) range.get("$gte"), (Date) range.get("$lte"))) { ZonedDateTime kd = date.toInstant().atZone(KOLKATA); String key = kd.format(ISO_DAY); revenueData.add(first(revenueMap.get(key), 0)); spendingData.add(first(spendingMap.get(key), 0)); categories.add(kd.format(DISPLAY_SHORT_DAY)); }
        List<Map<String, Object>> byService = new ArrayList<>();
        for (Document i : byServiceRaw) { Map<String, Object> row = new LinkedHashMap<>(); row.put("name", i.get("_id")); row.put("value", first(i.get("totalRevenue"), 0)); row.put("count", first(i.get("count"), 0)); byService.add(row); }
        double totalRevenue = sum(revenueData), totalSpending = sum(spendingData);
        Map<String, Object> dateRange = new LinkedHashMap<>(); dateRange.put("start", range.get("$gte")); dateRange.put("end", range.get("$lte")); dateRange.put("timeRange", query == null ? null : query.get("timeRange"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("revenueData", revenueData); r.put("spendingData", spendingData); r.put("categories", categories); r.put("revenueByService", byService); r.put("totalRevenue", totalRevenue); r.put("totalSpending", totalSpending); r.put("averageDailyRevenue", revenueData.isEmpty() ? 0 : totalRevenue / revenueData.size()); r.put("averageDailySpending", spendingData.isEmpty() ? 0 : totalSpending / spendingData.size()); r.put("netProfit", totalRevenue - totalSpending); r.put("dateRange", dateRange);
        return r;
    }

    static List<String> aggregationStageJsonForTests() {
        return List.of(
                "{ '$group': { '_id': null, 'totalFreeConsultation': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$eq': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } }, 'totalPaidConsultation': { '$sum': { '$cond': [ { '$and': [ { '$eq': ['$status', 'completed'] }, { '$ne': ['$coupon.code', '" + FREE_CHAT_COUPON_CODE + "'] } ] }, 1, 0 ] } } } }",
                "{ '$group': { '_id': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } }, 'date': { '$first': { '$dateToString': { 'format': '%Y-%m-%d', 'date': '$createdAt', 'timezone': 'Asia/Kolkata' } } }, 'consultationAttempt': { '$sum': 1 }, 'failedConsultation': { '$sum': { '$cond': [ { '$or': [ { '$eq': ['$status', 'canceled'] }, { '$eq': ['$status', 'missed'] } ] }, 1, 0 ] } } } }",
                "{ '$lookup': { 'from': '" + Collections.CONSULTANTS + "', 'let': { 'consultantId': '$consultant_id' }, 'pipeline': [ { '$match': { '$expr': { '$eq': [ { '$toObjectId': '$$consultantId' }, '$_id' ] } } } ], 'as': 'consultant' } }",
                "{ '$addFields': { 'consultants.percentage': { '$round': [ { '$multiply': [ { '$divide': ['$consultants.sessions', '$totalSessions'] }, 100 ] }, 2 ] } } }",
                "{ '$group': { '_id': { 'year': { '$year': '$createdAt' }, 'month': { '$month': '$createdAt' }, 'day': { '$dayOfMonth': '$createdAt' } }, 'totalRevenue': { '$sum': { '$cond': { 'if': { '$eq': ['$status', 'COMPLETED'] }, 'then': { '$ifNull': ['$paidAmount', 0] }, 'else': 0 } } }, 'count': { '$sum': 1 } } }"
        ).stream().map(s -> s.replace('\'', '"')).toList();
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        return mongo.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private static Document d(String json) { return Document.parse(json.replace('\'', '"')); }

    private Document quickStatsDateFilter(Map<String, String> input) {
        Document f = new Document();
        if (notBlank(input.get("timeRange")) || notBlank(input.get("timeRangeStartDate")) || notBlank(input.get("timeRangeEndDate"))) {
            try { f.put("createdAt", getUTCDateRangeQuery(input.get("timeRange"), input.get("timeRangeStartDate"), input.get("timeRangeEndDate"))); } catch (Exception ignored) { }
        }
        if (notBlank(input.get("timeFilter")) && !notBlank(input.get("timeRange"))) {
            try {
                String mapped = switch (input.get("timeFilter")) { case "today" -> "today"; case "week" -> "last7days"; case "month" -> "thisMonth"; default -> null; };
                if (mapped != null) f.put("createdAt", getUTCDateRangeQuery(mapped, null, null));
            } catch (Exception ignored) { }
        }
        return f;
    }

    private Document last10DaysStyleDateFilter(Map<String, String> input) {
        Document f = new Document();
        if (notBlank(input.get("timeFilter")) && notBlank(input.get("startDate")) && notBlank(input.get("endDate"))) {
            f.put("createdAt", new Document("$gte", parseJavaScriptDateOrNow(input.get("startDate"))).append("$lte", parseJavaScriptDateOrNow(input.get("endDate"))));
        } else if (notBlank(input.get("timeRange"))) {
            try { f.put("createdAt", getUTCDateRangeQuery(input.get("timeRange"), null, null)); } catch (Exception ignored) { f.put("createdAt", last10DaysRange()); }
        } else f.put("createdAt", last10DaysRange());
        return f;
    }

    private Document last10DaysRange() {
        ZonedDateTime now = ZonedDateTime.now(KOLKATA).with(LocalTime.MAX);
        return new Document("$gte", Date.from(now.minusDays(9).toLocalDate().atStartOfDay(KOLKATA).toInstant())).append("$lte", Date.from(now.toInstant()));
    }

    private Document getUTCDateRangeQuery(String timeRange, String startDate, String endDate) {
        ZonedDateTime now = ZonedDateTime.now(KOLKATA), start, end;
        switch (String.valueOf(timeRange)) {
            case "today" -> { start = now.toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "yesterday" -> { LocalDate d = now.minusDays(1).toLocalDate(); start = d.atStartOfDay(KOLKATA); end = d.atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "last7days" -> { start = now.minusDays(7).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "last30days" -> { start = now.minusDays(30).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "last60days" -> { start = now.minusDays(60).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "thisMonth" -> { start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "lastMonth" -> { ZonedDateTime m = now.minusMonths(1); start = m.withDayOfMonth(1).toLocalDate().atStartOfDay(KOLKATA); end = m.withDayOfMonth(m.toLocalDate().lengthOfMonth()).toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            case "custom" -> { if (!notBlank(startDate) || !notBlank(endDate)) throw new IllegalArgumentException("Custom date range requires both startDate and endDate"); start = parseMomentTz(startDate).toLocalDate().atStartOfDay(KOLKATA); end = parseMomentTz(endDate).toLocalDate().atTime(LocalTime.MAX).atZone(KOLKATA); }
            default -> throw new IllegalArgumentException("Unsupported timeRange: " + timeRange);
        }
        return new Document("$gte", Date.from(start.toInstant())).append("$lte", Date.from(end.toInstant()));
    }

    private ZonedDateTime selectedTargetDate(Map<String, String> input) {
        if (notBlank(input.get("date"))) return parseMomentUtcAware(input.get("date"));
        if (notBlank(input.get("startDate"))) return parseMomentUtcAware(input.get("startDate"));
        return ZonedDateTime.now(KOLKATA);
    }

    private ZonedDateTime parseMomentUtcAware(String raw) { return raw != null && raw.contains("Z") ? Instant.parse(raw).atZone(KOLKATA) : parseMomentTz(raw); }

    private ZonedDateTime parseMomentTz(String raw) {
        if (!notBlank(raw)) return ZonedDateTime.now(KOLKATA);
        try { return Instant.parse(raw).atZone(KOLKATA); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(raw).atZone(KOLKATA); } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(raw).atStartOfDay(KOLKATA); } catch (DateTimeParseException ignored) { return ZonedDateTime.now(KOLKATA); }
    }

    private Date parseJavaScriptDateOrNow(String raw) {
        if (!notBlank(raw)) return new Date();
        try { return Date.from(Instant.parse(raw)); } catch (DateTimeParseException ignored) { }
        try { return Date.from(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant()); } catch (DateTimeParseException ignored) { }
        try { return Date.from(LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)); } catch (DateTimeParseException ignored) { return new Date(); }
    }

    private Date endOfUtcDay(Date date) { return Date.from(date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC)); }

    private List<String> last10DaysReversed() {
        List<String> days = new ArrayList<>(); ZonedDateTime today = ZonedDateTime.now(KOLKATA);
        for (int i = 9; i >= 0; i--) days.add(today.minusDays(i).format(ISO_DAY));
        java.util.Collections.reverse(days); return days;
    }

    private List<Date> datesBetween(Date start, Date end) {
        List<Date> dates = new ArrayList<>(); ZonedDateTime cur = start.toInstant().atZone(ZoneId.systemDefault()), lim = end.toInstant().atZone(ZoneId.systemDefault());
        while (!cur.toInstant().isAfter(lim.toInstant())) { dates.add(Date.from(cur.toInstant())); cur = cur.plusDays(1); }
        return dates;
    }

    private Map<String, Object> valuesById(List<Document> rows, String field) { Map<String, Object> out = new LinkedHashMap<>(); for (Document row : rows) out.put(String.valueOf(row.get("_id")), row.get(field)); return out; }
    private Document findBy(List<Document> rows, String field, Object value) { for (Document row : rows) if (String.valueOf(value).equals(String.valueOf(row.get(field)))) return row; return null; }
    private List<Object> fieldList(List<Document> rows, String field) { List<Object> out = new ArrayList<>(); for (Document row : rows) out.add(row.get(field)); return out; }
    private Object first(Object value, Object fallback) { return truthy(value) ? value : fallback; }
    private boolean truthy(Object value) { if (value == null) return false; if (value instanceof Boolean b) return b; if (value instanceof Number n) return n.doubleValue() != 0; return !String.valueOf(value).isEmpty(); }
    private double firstTruthyNumber(Object value, double fallback) { return truthy(value) ? number(value) : fallback; }
    private double number(Object value) { if (value instanceof Decimal128 d) return d.bigDecimalValue().doubleValue(); if (value instanceof Number n) return n.doubleValue(); try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; } }
    private Object jsNumber(double value) { return value == Math.rint(value) ? (long) value : value; }
    private double round(double value, int places) { double s = Math.pow(10, places); return Math.round(value * s) / s; }
    private String fixed(double value, int places) { return String.format(Locale.US, "%." + places + "f", value); }
    private double sum(List<Object> values) { double t = 0; for (Object v : values) t += number(v); return t; }
    private boolean notBlank(String text) { return text != null && !text.isBlank(); }
    private String v(Map<String, String> query, String key) { return query == null ? null : query.get(key); }
}
