package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminUserInsightsService {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private final MongoTemplate mongo;
    @SuppressWarnings("unused")
    private final AdminMongoSupport support;

    public AdminUserInsightsService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> getUserInsights(Map<String, String> query) {
        try {
            String pageText = text(query, "page", "1");
            String pageSizeText = text(query, "pageSize", "10");
            String search = text(query, "search", "");
            String startDate = text(query, "startDate", null);
            String endDate = text(query, "endDate", null);
            String timeRange = text(query, "timeRange", null);
            String consultantType = text(query, "consultantType", null);
            String problemType = text(query, "problemType", null);
            String userGender = text(query, "userGender", null);
            String consultantGender = text(query, "consultantGender", null);
            String timeZone = text(query, "timeZone", null);

            int page = parseInt(pageText, 1);
            int limit = Integer.parseInt(pageSizeText);
            int skip = (page - 1) * parseInt(pageSizeText, 10);
            buildDateFilter(startDate, endDate, timeRange);
            // FAITHFUL(node-quirk): user-insights-service.js:21 sortField/sortDirection default to date/desc but are never applied; aggregation sort wins.

            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("timeRange", timeRange);
            filters.put("consultantType", consultantType);
            filters.put("problemType", problemType);
            filters.put("userGender", userGender);
            filters.put("consultantGender", consultantGender);
            filters.put("timeZone", timeZone);
            List<Document> dateWiseInsights = getDateWiseInsights(startDate, endDate, search, filters);
            int totalDocuments = dateWiseInsights.size();
            List<Document> paginatedData = new ArrayList<>(dateWiseInsights.subList(Math.min(skip, totalDocuments), Math.min(skip + limit, totalDocuments)));
            List<Document> dataWithFilters = new ArrayList<>();
            for (Document item : paginatedData) {
                Document copy = new Document(item);
                copy.put("consultantType", truthy(consultantType) ? consultantType : null);
                copy.put("problemType", truthy(problemType) ? problemType : null);
                copy.put("consultantGender", truthy(consultantGender) ? consultantGender : null);
                copy.put("userGender", truthy(userGender) ? userGender : null);
                copy.put("timeZone", truthy(timeZone) ? timeZone : null);
                dataWithFilters.add(copy);
            }
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("totalDocuments", totalDocuments);
            pagination.put("currentPage", page);
            pagination.put("totalPages", (int) Math.ceil(totalDocuments / (double) limit));
            pagination.put("hasNextPage", page * limit < totalDocuments);
            pagination.put("hasPrevPage", page > 1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", dataWithFilters);
            result.put("pagination", pagination);
            result.put("totals", calculateTotals(dateWiseInsights));
            return result;
        } catch (Exception error) {
            throw new RuntimeException("Failed to fetch user insights");
        }
    }

    Document buildDateFilter(String startDate, String endDate, String timeRange) {
        Document dateFilter = new Document();
        if (truthy(timeRange) && !"custom".equals(timeRange)) {
            Document range = utcDateRangeQuery(timeRange);
            if (!range.isEmpty()) dateFilter.put("createdAt", range);
        } else if (truthy(startDate) && truthy(endDate)) {
            dateFilter.put("createdAt", new Document("$gte", parseDate(startDate)).append("$lte", parseDate(endDate)));
        }
        return dateFilter;
    }

    private Document utcDateRangeQuery(String timeRange) {
        try {
            ZonedDateTime now = ZonedDateTime.now(KOLKATA);
            ZonedDateTime start;
            ZonedDateTime end;
            switch (timeRange) {
                case "today" -> { start = now.toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(23, 59, 59, 999_000_000).atZone(KOLKATA); }
                case "last7days" -> { start = now.minusDays(7).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(23, 59, 59, 999_000_000).atZone(KOLKATA); }
                case "last30days" -> { start = now.minusDays(30).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(23, 59, 59, 999_000_000).atZone(KOLKATA); }
                case "last60days" -> { start = now.minusDays(60).toLocalDate().atStartOfDay(KOLKATA); end = now.toLocalDate().atTime(23, 59, 59, 999_000_000).atZone(KOLKATA); }
                default -> throw new IllegalArgumentException("Unsupported timeRange: " + timeRange);
            }
            return new Document("$gte", Date.from(start.toInstant())).append("$lte", Date.from(end.toInstant()));
        } catch (Exception ignored) {
            // FAITHFUL(node-quirk): user-insights-service.js:99 catches unsupported validator-allowed timeRange (e.g. last90days) and silently drops the date filter.
            return new Document();
        }
    }

    Document getTimeZoneFilter(String timeZone) {
        Map<String, int[]> slots = Map.ofEntries(
                Map.entry("12pm-2pm", new int[]{12, 14}), Map.entry("2pm-4pm", new int[]{14, 16}),
                Map.entry("4pm-6pm", new int[]{16, 18}), Map.entry("6pm-8pm", new int[]{18, 20}),
                Map.entry("8pm-10pm", new int[]{20, 22}), Map.entry("10pm-12am", new int[]{22, 24}),
                Map.entry("12am-2am", new int[]{0, 2}), Map.entry("2am-4am", new int[]{2, 4}),
                Map.entry("4am-6am", new int[]{4, 6}), Map.entry("6am-8am", new int[]{6, 8}),
                Map.entry("8am-10am", new int[]{8, 10}), Map.entry("10am-12pm", new int[]{10, 12}));
        int[] slot = slots.get(timeZone);
        return slot == null ? new Document() : new Document("$hour", new Document("$gte", slot[0]).append("$lt", slot[1]));
    }

    List<Document> getDateWiseInsights(String startDate, String endDate, String search, Map<String, String> filters) {
        try {
            Document waitlistFilters = new Document(buildDateFilter(startDate, endDate, filters.get("timeRange")));
            if (truthy(filters.get("consultantType"))) waitlistFilters.put("consultant.primarySkills", new Document("$in", List.of(filters.get("consultantType"))));
            if (truthy(filters.get("problemType"))) waitlistFilters.put("request_form.concern", filters.get("problemType"));
            if (truthy(filters.get("userGender"))) waitlistFilters.put("user.details.gender", filters.get("userGender"));
            if (truthy(filters.get("consultantGender"))) waitlistFilters.put("consultant.details.gender", filters.get("consultantGender"));
            if (truthy(filters.get("timeZone"))) {
                Document createdAt = waitlistFilters.get("createdAt", new Document());
                createdAt.putAll(getTimeZoneFilter(filters.get("timeZone")));
                waitlistFilters.put("createdAt", createdAt);
                // FAITHFUL(node-quirk): user-insights-service.js:165 merges {$hour:{...}} into a field query instead of using $expr.
            }
            List<Document> waitlistData;
            try {
                waitlistData = mongo.getCollection(Collections.WAITLISTS).aggregate(buildDateWiseWaitlistPipeline(waitlistFilters)).into(new ArrayList<>());
            } catch (Exception ignored) {
                return new ArrayList<>();
            }
            Document rechargeDateFilter = new Document();
            if (truthy(startDate) && truthy(endDate)) {
                rechargeDateFilter.put("createdAt", new Document("$gte", parseDate(startDate)).append("$lte", parseDate(endDate)));
            }
            List<Document> rechargeData;
            try {
                rechargeData = mongo.getCollection(Collections.TRANSACTIONS).aggregate(buildRechargePipeline(rechargeDateFilter)).into(new ArrayList<>());
            } catch (Exception ignored) {
                rechargeData = new ArrayList<>();
            }
            List<Document> mergedData = mergeDateWiseData(waitlistData, List.of(), rechargeData);
            if (truthy(search)) {
                List<Document> filtered = new ArrayList<>();
                for (Document item : mergedData) {
                    if (String.valueOf(item.get("date")).contains(search)
                            || String.valueOf(item.get("totalAttempts")).contains(search)
                            || String.valueOf(item.get("totalNewUserRegistrations")).contains(search)
                            || String.valueOf(item.get("totalSpending")).contains(search)) filtered.add(item);
                }
                return filtered;
            }
            return mergedData;
        } catch (Exception error) {
            throw new RuntimeException("Failed to fetch date-wise insights");
        }
    }

    List<Document> buildDateWiseWaitlistPipeline(Document waitlistFilters) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(p("{\"$lookup\":{\"from\":\"" + Collections.CONSULTANTS + "\",\"let\":{\"consultantId\":\"$consultant_id\"},\"pipeline\":[{\"$match\":{\"$expr\":{\"$eq\":[{\"$toObjectId\":\"$$consultantId\"},\"$_id\"]}}}],\"as\":\"consultant\"}}"));
        pipeline.add(p("{\"$lookup\":{\"from\":\"" + Collections.USERS + "\",\"let\":{\"userId\":\"$user_id\"},\"pipeline\":[{\"$match\":{\"$expr\":{\"$eq\":[{\"$toObjectId\":\"$$userId\"},\"$_id\"]}}}],\"as\":\"user\"}}"));
        pipeline.add(p("{\"$unwind\":{\"path\":\"$consultant\",\"preserveNullAndEmptyArrays\":true}}"));
        pipeline.add(p("{\"$unwind\":{\"path\":\"$user\",\"preserveNullAndEmptyArrays\":true}}"));
        pipeline.add(new Document("$match", waitlistFilters));
        pipeline.add(p("""
{"$group":{"_id":{"$dateToString":{"format":"%Y-%m-%d","date":"$createdAt","timezone":"Asia/Kolkata"}},"newUserRegistrations":{"$sum":{"$cond":[{"$and":[{"$ne":["$user.createdAt",null]},{"$eq":[{"$dateToString":{"format":"%Y-%m-%d","date":"$user.createdAt","timezone":"Asia/Kolkata"}},{"$dateToString":{"format":"%Y-%m-%d","date":"$createdAt","timezone":"Asia/Kolkata"}}]}]},1,0]}},"totalAttempts":{"$sum":1},"totalCompleted":{"$sum":{"$cond":[{"$eq":["$status","completed"]},1,0]}},"totalSpending":{"$sum":{"$ifNull":["$onCompletion.amountToDeduct",0]}},"totalChatSpending":{"$sum":{"$cond":[{"$eq":["$session_info.mode","chat"]},{"$ifNull":["$onCompletion.amountToDeduct",0]},0]}},"totalCallSpending":{"$sum":{"$cond":[{"$in":["$session_info.mode",["audio","video"]]},{"$ifNull":["$onCompletion.amountToDeduct",0]},0]}},"totalChatAttempts":{"$sum":{"$cond":[{"$eq":["$session_info.mode","chat"]},1,0]}},"totalCallAttempts":{"$sum":{"$cond":[{"$in":["$session_info.mode",["audio","video"]]},1,0]}},"totalChatCompleted":{"$sum":{"$cond":[{"$and":[{"$eq":["$session_info.mode","chat"]},{"$eq":["$status","completed"]}]},1,0]}},"totalCallCompleted":{"$sum":{"$cond":[{"$and":[{"$in":["$session_info.mode",["audio","video"]]},{"$eq":["$status","completed"]}]},1,0]}},"totalFreeChats":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$session_info.mode","chat"]},{"$eq":["$coupon.code","FREE5MINUTES"]}]},1,0]}},"totalPaidChats":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$session_info.mode","chat"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},1,0]}},"totalFreeCalls":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$in":["$session_info.mode",["audio","video"]]},{"$eq":["$coupon.code","FREE5MINUTES"]}]},1,0]}},"totalPaidCalls":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$in":["$session_info.mode",["audio","video"]]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},1,0]}},"totalTimeInSeconds":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalFreeChatsTime":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$session_info.mode","chat"]},{"$eq":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalPaidChatsTime":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$session_info.mode","chat"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalFreeCallsTime":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$in":["$session_info.mode",["audio","video"]]},{"$eq":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalPaidCallsTime":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$in":["$session_info.mode",["audio","video"]]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalRepeatChatsCalls":{"$sum":{"$cond":[{"$and":[{"$ne":["$user_id",null]}]},1,0]}},"totalFirstChatsCalls":{"$sum":{"$cond":[{"$and":[{"$ne":["$user_id",null]}]},1,0]}},"totalFailedAttempts":{"$sum":{"$cond":[{"$or":[{"$eq":["$status","canceled"]},{"$eq":["$status","missed"]}]},1,0]}},"uniquePaidCallUsers":{"$addToSet":{"$cond":[{"$and":[{"$ne":["$user_id",null]},{"$eq":["$status","completed"]},{"$in":["$session_info.mode",["audio","video"]]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},"$user_id","$$REMOVE"]}},"uniquePaidChatUsers":{"$addToSet":{"$cond":[{"$and":[{"$ne":["$user_id",null]},{"$eq":["$status","completed"]},{"$eq":["$session_info.mode","chat"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},"$user_id","$$REMOVE"]}},"totalUniqueFirstTimePaidChats":{"$sum":{"$cond":[{"$and":[{"$eq":["$session_info.mode","chat"]},{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalUniqueFirstTimePaidCalls":{"$sum":{"$cond":[{"$and":[{"$in":["$session_info.mode",["audio","video"]]},{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"uniqueFirstTimePaidChatUsers":{"$addToSet":{"$cond":[{"$and":[{"$eq":["$session_info.mode","chat"]},{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"userId":"$user_id","duration":{"$ifNull":["$onCompletion.callDurationInSeconds",0]},"coin":{"$ifNull":["$onCompletion.amountToDeduct",0]}},"$$REMOVE"]}},"uniqueFirstTimePaidCallUsers":{"$addToSet":{"$cond":[{"$and":[{"$in":["$session_info.mode",["audio","video"]]},{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"userId":"$user_id","duration":{"$ifNull":["$onCompletion.callDurationInSeconds",0]},"coin":{"$ifNull":["$onCompletion.amountToDeduct",0]}},"$$REMOVE"]}},"consultantTypes":{"$addToSet":"$consultant.primarySkills"},"problemTypes":{"$addToSet":"$request_form.concern"},"consultantGenders":{"$addToSet":"$consultant.details.gender"},"userGenders":{"$addToSet":"$user.gender"},"uniqueUsers":{"$addToSet":"$user_id"},"consultationDate":{"$first":"$createdAt"},"chatTransactions":{"$sum":{"$cond":[{"$and":[{"$eq":["$session_info.mode","chat"]},{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.amountToDeduct",0]},0]}},"callTransactions":{"$sum":{"$cond":[{"$and":[{"$in":["$session_info.mode",["audio","video"]]},{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.amountToDeduct",0]},0]}},"uniquePaidCallUsersCoins":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$in":["$session_info.mode",["audio","video"]]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.amountToDeduct",0]},0]}},"uniquePaidChatUsersCoins":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$session_info.mode","chat"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.amountToDeduct",0]},0]}}}}
"""));
        pipeline.add(p("{\"$addFields\":{\"uniqueFirstTimePaidChatUsers\":{\"$map\":{\"input\":{\"$setUnion\":[\"$uniqueFirstTimePaidChatUsers.userId\",[]]},\"as\":\"uid\",\"in\":{\"$first\":{\"$filter\":{\"input\":\"$uniqueFirstTimePaidChatUsers\",\"as\":\"u\",\"cond\":{\"$eq\":[\"$$u.userId\",\"$$uid\"]}}}}}},\"uniqueFirstTimePaidCallUsers\":{\"$map\":{\"input\":{\"$setUnion\":[\"$uniqueFirstTimePaidCallUsers.userId\",[]]},\"as\":\"uid\",\"in\":{\"$first\":{\"$filter\":{\"input\":\"$uniqueFirstTimePaidCallUsers\",\"as\":\"u\",\"cond\":{\"$eq\":[\"$$u.userId\",\"$$uid\"]}}}}}}}}"));
        pipeline.add(p("{\"$addFields\":{\"totalUniqueFirstTimePaidChats\":{\"$reduce\":{\"input\":\"$uniqueFirstTimePaidChatUsers\",\"initialValue\":0,\"in\":{\"$add\":[\"$$value\",\"$$this.duration\"]}}},\"totalUniqueFirstTimePaidCalls\":{\"$reduce\":{\"input\":\"$uniqueFirstTimePaidCallUsers\",\"initialValue\":0,\"in\":{\"$add\":[\"$$value\",\"$$this.duration\"]}}},\"totalUniqueFirstTimePaidChatsCoins\":{\"$reduce\":{\"input\":\"$uniqueFirstTimePaidChatUsers\",\"initialValue\":0,\"in\":{\"$add\":[\"$$value\",\"$$this.coin\"]}}},\"totalUniqueFirstTimePaidCallsCoins\":{\"$reduce\":{\"input\":\"$uniqueFirstTimePaidCallUsers\",\"initialValue\":0,\"in\":{\"$add\":[\"$$value\",\"$$this.coin\"]}}}}}"));
        pipeline.add(p("{\"$project\":{\"_id\":0,\"date\":\"$_id\",\"totalAttempts\":1,\"totalCompleted\":1,\"newUserRegistrations\":1,\"totalChatAttempts\":1,\"totalCallAttempts\":1,\"totalChatCompleted\":1,\"totalCallCompleted\":1,\"totalSpending\":1,\"totalChatSpending\":1,\"totalCallSpending\":1,\"totalFreeChats\":1,\"totalPaidChats\":1,\"totalFreeCalls\":1,\"uniquePaidCallUsers\":{\"$size\":\"$uniquePaidCallUsers\"},\"uniquePaidChatUsers\":{\"$size\":\"$uniquePaidChatUsers\"},\"totalPaidCalls\":1,\"totalTimeInSeconds\":1,\"totalFreeChatsTime\":1,\"totalPaidChatsTime\":1,\"totalFreeCallsTime\":1,\"totalPaidCallsTime\":1,\"totalRepeatChatsCalls\":1,\"totalFirstChatsCalls\":1,\"totalUniqueFirstTimePaidChats\":1,\"totalUniqueFirstTimePaidCalls\":1,\"uniqueFirstTimePaidChatUsers\":1,\"uniqueFirstTimePaidCallUsers\":1,\"totalFailedAttempts\":1,\"consultantTypes\":1,\"problemTypes\":1,\"consultantGenders\":1,\"userGenders\":1,\"uniqueUsers\":{\"$size\":\"$uniqueUsers\"},\"consultationDate\":1,\"totalUniqueFirstTimePaidChatsCoins\":1,\"totalUniqueFirstTimePaidCallsCoins\":1}}"));
        pipeline.add(p("{\"$sort\":{\"date\":-1}}"));
        return pipeline;
    }

    List<Document> buildRechargePipeline(Document dateFilter) {
        return List.of(new Document("$match", new Document(dateFilter).append("status", "COMPLETED")),
                p("{\"$group\":{\"_id\":{\"$dateToString\":{\"format\":\"%Y-%m-%d\",\"date\":\"$createdAt\",\"timezone\":\"Asia/Kolkata\"}},\"totalRecharge\":{\"$sum\":1},\"uniqueRechargeUsers\":{\"$addToSet\":\"$userId\"}}}"),
                p("{\"$project\":{\"_id\":0,\"date\":\"$_id\",\"totalRecharge\":1,\"uniqueRechargeUsers\":{\"$size\":\"$uniqueRechargeUsers\"}}}"),
                p("{\"$sort\":{\"date\":-1}}"));
    }

    List<Document> mergeDateWiseData(List<Document> waitlistData, List<Document> spendingData, List<Document> rechargeData) {
        Map<String, Document> mergedMap = new LinkedHashMap<>();
        for (Document item : waitlistData) {
            Document m = new Document();
            m.put("date", item.get("date"));
            m.put("consultationDate", formatMoment(item.get("consultationDate")));
            putMetrics(m, item);
            mergedMap.put(String.valueOf(item.get("date")), m);
        }
        for (Document item : rechargeData) {
            String date = String.valueOf(item.get("date"));
            if (mergedMap.containsKey(date)) {
                mergedMap.get(date).put("totalRecharge", n0(item.get("totalRecharge")));
                mergedMap.get(date).put("uniqueRechargeUsers", n0(item.get("uniqueRechargeUsers")));
            } else {
                Document m = zeroRow(date);
                m.put("totalRecharge", n0(item.get("totalRecharge")));
                m.put("uniqueRechargeUsers", n0(item.get("uniqueRechargeUsers")));
                // FAITHFUL(node-quirk): user-insights-service.js:1143 recharge-only rows use current moment, not the recharge date, for consultationDate.
                mergedMap.put(date, m);
            }
        }
        List<Document> out = new ArrayList<>();
        for (Document item : mergedMap.values()) out.add(projectMerged(item));
        return out;
    }

    private void putMetrics(Document m, Document item) {
        m.put("totalAttempts", item.get("totalAttempts"));
        m.put("newUserRegistrations", item.get("newUserRegistrations"));
        m.put("totalSpending", n0(item.get("totalSpending")));
        m.put("totalChatSpending", n0(item.get("totalChatSpending")));
        m.put("totalCallSpending", n0(item.get("totalCallSpending")));
        m.put("totalCompleted", n0(item.get("totalCompleted")));
        m.put("totalPaidChats", n0(item.get("totalPaidChats")));
        m.put("totalPaidCalls", n0(item.get("totalPaidCalls")));
        m.put("totalChatCompleted", n0(item.get("totalChatCompleted")));
        m.put("totalCallCompleted", n0(item.get("totalCallCompleted")));
        m.put("totalUniqueFirstTimePaidChats", n0(item.get("totalUniqueFirstTimePaidChats")));
        m.put("totalUniqueFirstTimePaidCalls", n0(item.get("totalUniqueFirstTimePaidCalls")));
        m.put("totalUniqueFirstTimePaidChatsCoins", n0(item.get("totalUniqueFirstTimePaidChatsCoins")));
        m.put("totalUniqueFirstTimePaidCallsCoins", n0(item.get("totalUniqueFirstTimePaidCallsCoins")));
        m.put("totalNewUserRegistrations", n0(item.get("newUserRegistrations")));
        m.put("totalAttemptsChats", n0(item.get("totalChatAttempts")));
        m.put("totalCompletedChats", n0(item.get("totalChatCompleted")));
        m.put("totalFreeChats", n0(item.get("totalFreeChats")));
        m.put("uniqueFirstTimePaidChatUsers", jsOr(item.get("uniqueFirstTimePaidChatUsers"), 0));
        m.put("uniqueFirstTimePaidCallUsers", jsOr(item.get("uniqueFirstTimePaidCallUsers"), 0));
        m.put("uniquePaidCallUsers", n0(item.get("uniquePaidCallUsers")));
        m.put("uniquePaidChatUsers", n0(item.get("uniquePaidChatUsers")));
        m.put("uniquePaidCallUsersCoins", 0);
        m.put("uniquePaidChatUsersCoins", 0);
        m.put("totalFirstChats", n0(item.get("uniquePaidChatUsers")));
        m.put("totalRepeatChats", max0Or0(subRaw(item.get("totalPaidChats"), item.get("uniquePaidChatUsers"))));
        m.put("totalFirstCalls", n0(item.get("uniquePaidCallUsers")));
        m.put("totalRepeatCalls", max0Or0(subRaw(item.get("totalPaidCalls"), item.get("uniquePaidCallUsers"))));
        m.put("totalAttemptsCalls", n0(item.get("totalCallAttempts")));
        m.put("totalCompletedCalls", n0(item.get("totalCallCompleted")));
        m.put("totalFreeCalls", n0(item.get("totalFreeCalls")));
        m.put("totalTime", item.get("totalTimeInSeconds"));
        m.put("avgTime", divOr0(item.get("totalTimeInSeconds"), item.get("totalAttempts")));
        m.put("totalTimeChats", jsOr0(addRaw(item.get("totalFreeChatsTime"), item.get("totalPaidChatsTime"))));
        m.put("avgTimeChats", divOr0(addRaw(item.get("totalFreeChatsTime"), item.get("totalPaidChatsTime")), addRaw(item.get("totalFreeChats"), item.get("totalPaidChats"))));
        m.put("totalTimeFreeChat", n0(item.get("totalFreeChatsTime")));
        m.put("avgTimeFreeChat", divOr0(n0(item.get("totalFreeChatsTime")), item.get("totalFreeChats")));
        m.put("totalTimePaidChat", n0(item.get("totalPaidChatsTime")));
        m.put("avgTimePaidChat", divOr0(n0(item.get("totalPaidChatsTime")), item.get("totalPaidChats")));
        m.put("totalTimeFirstChat", n0(item.get("totalUniqueFirstTimePaidChats")));
        m.put("avgTimeFirstChat", divOr0(item.get("totalUniqueFirstTimePaidChats"), item.get("uniquePaidChatUsers")));
        m.put("totalTimeRepeatChats", num(item.get("totalUniqueFirstTimePaidChats")) > 0 ? subRaw(item.get("totalPaidChatsTime"), item.get("totalUniqueFirstTimePaidChats")) : 0);
        m.put("avgTimeRepeatChats", num(item.get("totalUniqueFirstTimePaidChats")) > 0 ? divOr0(subRaw(item.get("totalPaidChatsTime"), item.get("totalUniqueFirstTimePaidChats")), max0Or0(subRaw(item.get("totalPaidChats"), item.get("uniquePaidChatUsers")))) : 0);
        m.put("avgUniqueFirstTimePaidChats", divOr0(n0(item.get("totalUniqueFirstTimePaidChats")), jsOr(item.get("uniquePaidChatUsers"), 1)));
        m.put("totalTimeCalls", n0(item.get("totalFreeCallsTime")) + n0(item.get("totalPaidCallsTime")));
        m.put("avgTimeCalls", divOr0(n0(item.get("totalFreeCallsTime")) + n0(item.get("totalPaidCallsTime")), addRaw(item.get("totalFreeCalls"), item.get("totalPaidCalls"))));
        m.put("totalTimeFreeCalls", n0(item.get("totalFreeCallsTime")));
        m.put("avgTimeFreeCalls", divOr0(n0(item.get("totalFreeCallsTime")), item.get("totalFreeCalls")));
        m.put("totalTimePaidCalls", n0(item.get("totalPaidCallsTime")));
        m.put("avgTimePaidCalls", divOr0(n0(item.get("totalPaidCallsTime")), item.get("totalPaidCalls")));
        m.put("totalTimeFirstCalls", n0(item.get("totalUniqueFirstTimePaidCalls")));
        m.put("avgTimeFirstCalls", divOr0(item.get("totalUniqueFirstTimePaidCalls"), item.get("uniquePaidCallUsers")));
        m.put("totalTimeRepeatCalls", num(item.get("totalUniqueFirstTimePaidCalls")) > 0 ? subRaw(item.get("totalPaidCallsTime"), item.get("totalUniqueFirstTimePaidCalls")) : 0);
        m.put("avgTimeRepeatCalls", num(item.get("totalUniqueFirstTimePaidCalls")) > 0 ? divOr0(subRaw(item.get("totalPaidCallsTime"), item.get("totalUniqueFirstTimePaidCalls")), max0Or0(subRaw(item.get("totalPaidCalls"), item.get("uniquePaidCallUsers")))) : 0);
        m.put("avgSpending", divOr0(item.get("totalSpending"), addRaw(item.get("totalPaidChats"), item.get("totalPaidCalls"))));
        m.put("totalPaidChatSpending", n0(item.get("totalChatSpending")));
        m.put("avgPaidChatSpending", divOr0(item.get("totalChatSpending"), item.get("totalPaidChats")));
        m.put("totalFirstPaidChatSpending", n0(item.get("totalUniqueFirstTimePaidChatsCoins")));
        m.put("avgFirstPaidChatSpending", divOr0(item.get("totalUniqueFirstTimePaidChatsCoins"), item.get("uniquePaidChatUsers")));
        m.put("totalRepeatPaidChatSpending", jsOr0(subRaw(item.get("totalChatSpending"), item.get("totalUniqueFirstTimePaidChatsCoins"))));
        m.put("avgRepeatPaidChatSpending", divOr0(subRaw(item.get("totalChatSpending"), item.get("totalUniqueFirstTimePaidChatsCoins")), max0Or0(subRaw(item.get("totalPaidChats"), item.get("uniquePaidChatUsers")))));
        m.put("totalPaidCallsSpending", n0(item.get("totalCallSpending")));
        m.put("avgPaidCallsSpending", divOr0(item.get("totalCallSpending"), item.get("totalPaidCalls")));
        m.put("totalFirstPaidCallsSpending", n0(item.get("totalUniqueFirstTimePaidCallsCoins")));
        m.put("avgFirstPaidCallsSpending", divOr0(item.get("totalUniqueFirstTimePaidCallsCoins"), item.get("uniquePaidCallUsers")));
        m.put("totalRepeatPaidCallsSpending", jsOr0(subRaw(item.get("totalCallSpending"), item.get("totalUniqueFirstTimePaidCallsCoins"))));
        m.put("avgRepeatPaidCallsSpending", divOr0(subRaw(item.get("totalCallSpending"), item.get("totalUniqueFirstTimePaidCallsCoins")), max0Or0(subRaw(item.get("totalPaidCalls"), item.get("uniquePaidCallUsers")))));
        m.put("totalRecharge", 0);
        m.put("uniqueRechargeUsers", 0);
        m.put("uniqueUsers", item.get("uniqueUsers"));
        m.put("consultantTypes", jsOr(item.get("consultantTypes"), List.of()));
        m.put("problemTypes", jsOr(item.get("problemTypes"), List.of()));
        m.put("consultantGenders", jsOr(item.get("consultantGenders"), List.of()));
        m.put("userGenders", jsOr(item.get("userGenders"), List.of()));
        m.put("totalTransactions", 0); m.put("totalCredits", 0); m.put("sessionBookings", 0); m.put("consultTransactions", 0); m.put("privateCallTransactions", 0);
        // FAITHFUL(node-quirk): user-insights-service.js:898/911 uses paid unique users as first counts and repeat=max(0,totalPaid-uniquePaid), not first-ever history.
    }

    private Document zeroRow(String date) {
        Document m = new Document("date", date).append("consultationDate", formatMoment(new Date()));
        for (String k : List.of("totalAttempts", "newUserRegistrations", "totalSpending", "totalChatSpending", "totalCallSpending", "totalCompleted", "totalPaidChats", "totalPaidCalls", "totalChatCompleted", "totalCallCompleted", "totalAttemptsChats", "totalCompletedChats", "totalFreeChats", "uniqueFirstTimePaidChatUsers", "uniqueFirstTimePaidCallUsers", "uniquePaidCallUsers", "uniquePaidChatUsers", "uniquePaidCallUsersCoins", "uniquePaidChatUsersCoins", "totalFirstChats", "totalRepeatChats", "totalFirstCalls", "totalRepeatCalls", "totalAttemptsCalls", "totalCompletedCalls", "totalFreeCalls", "totalTime", "avgTime", "totalTimeChats", "avgTimeChats", "totalTimeFreeChat", "avgTimeFreeChat", "totalTimePaidChat", "avgTimePaidChat", "totalTimeFirstChat", "avgTimeFirstChat", "totalTimeRepeatChats", "avgTimeRepeatChats", "totalTimeCalls", "avgTimeCalls", "totalTimeFreeCalls", "avgTimeFreeCalls", "totalTimePaidCalls", "avgTimePaidCalls", "totalTimeFirstCalls", "avgTimeFirstCalls", "totalTimeRepeatCalls", "avgTimeRepeatCalls", "avgSpending", "totalPaidChatSpending", "avgPaidChatSpending", "totalFirstPaidChatSpending", "avgFirstPaidChatSpending", "totalRepeatPaidChatSpending", "avgRepeatPaidChatSpending", "totalPaidCallsSpending", "avgPaidCallsSpending", "totalFirstPaidCallsSpending", "avgFirstPaidCallsSpending", "totalRepeatPaidCallsSpending", "avgRepeatPaidCallsSpending", "uniqueUsers", "totalTransactions", "totalCredits", "sessionBookings", "consultTransactions", "privateCallTransactions", "totalUniqueFirstTimePaidChats", "totalUniqueFirstTimePaidCalls")) m.put(k, 0);
        m.put("consultantTypes", List.of()); m.put("problemTypes", List.of()); m.put("consultantGenders", List.of()); m.put("userGenders", List.of());
        return m;
    }

    private Document projectMerged(Document item) {
        Document d = new Document();
        d.put("_id", item.get("date")); d.put("date", item.get("date")); d.put("consultationDate", item.get("consultationDate"));
        d.put("totalAttempts", n0(item.get("totalAttempts"))); d.put("totalCompleted", n0(item.get("totalCompleted"))); d.put("totalFailed", subOr0(item.get("totalAttempts"), item.get("totalCompleted"))); d.put("totalNewUserRegistrations", n0(item.get("newUserRegistrations")));
        d.put("totalAttemptsChats", item.get("totalAttemptsChats")); d.put("totalFailedChats", subOr0(item.get("totalAttemptsChats"), item.get("totalCompletedChats"))); d.put("totalCompletedChats", item.get("totalCompletedChats")); d.put("totalFreeChats", item.get("totalFreeChats")); d.put("totalPaidChats", item.get("totalPaidChats")); d.put("totalFirstChats", item.get("totalFirstChats")); d.put("totalRepeatChats", item.get("totalRepeatChats")); d.put("totalFirstChatsCalls", item.get("totalFirstChatsCalls")); d.put("totalRepeatChatsCalls", item.get("totalRepeatChatsCalls"));
        d.put("totalAttemptsCalls", item.get("totalAttemptsCalls")); d.put("totalFailedCalls", subOr0(item.get("totalAttemptsCalls"), item.get("totalCompletedCalls"))); d.put("totalCompletedCalls", item.get("totalCompletedCalls")); d.put("totalFreeCalls", item.get("totalFreeCalls")); d.put("totalPaidCalls", item.get("totalPaidCalls")); d.put("totalFirstCalls", item.get("totalFirstCalls")); d.put("totalRepeatCalls", item.get("totalRepeatCalls"));
        for (String k : List.of("totalTime", "avgTime", "totalTimeChats", "avgTimeChats", "totalTimeFreeChat", "avgTimeFreeChat", "totalTimePaidChat", "avgTimePaidChat", "totalTimeFirstChat", "avgTimeFirstChat", "totalTimeRepeatChats", "avgTimeRepeatChats", "totalUniqueFirstTimePaidChats", "avgUniqueFirstTimePaidChats", "uniqueFirstTimePaidCallUsers", "uniqueFirstTimePaidChatUsers", "totalTimeCalls", "avgTimeCalls", "totalTimeFreeCalls", "avgTimeFreeCalls", "totalTimePaidCalls", "avgTimePaidCalls", "totalTimeFirstCalls", "avgTimeFirstCalls", "totalTimeRepeatCalls", "avgTimeRepeatCalls", "totalSpending", "avgSpending", "totalPaidChatSpending", "avgPaidChatSpending", "totalFirstPaidChatSpending", "avgFirstPaidChatSpending", "totalRepeatPaidChatSpending", "avgRepeatPaidChatSpending", "totalPaidCallsSpending", "avgPaidCallsSpending", "totalFirstPaidCallsSpending", "avgFirstPaidCallsSpending", "totalRepeatPaidCallsSpending", "avgRepeatPaidCallsSpending", "totalRecharge", "uniqueRechargeUsers", "uniquePaidCallUsers", "uniquePaidChatUsers", "uniquePaidCallUsersCoins", "uniquePaidChatUsersCoins", "uniqueUsers", "totalWaiting", "totalCanceled", "totalMissed", "totalTransactions", "totalCredits", "sessionBookings", "consultTransactions", "privateCallTransactions", "consultantTypes", "problemTypes", "consultantGenders", "userGenders")) d.put(k, item.get(k));
        return d;
    }

    public Map<String, Object> getUserInsightsSummary(Map<String, String> query) {
        try {
            String startDate = text(query, "startDate", null);
            String endDate = text(query, "endDate", null);
            Document dateFilter = new Document();
            if (truthy(startDate) && truthy(endDate)) dateFilter.put("createdAt", new Document("$gte", parseDate(startDate)).append("$lte", parseDate(endDate)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalUsers", mongo.getCollection(Collections.USERS).countDocuments(dateFilter));
            result.put("activeUsers", mongo.getCollection(Collections.USERS).countDocuments(new Document(dateFilter).append("isActive", true)));
            result.putAll(getOverallStatistics(startDate, endDate));
            return result;
        } catch (Exception error) {
            throw new RuntimeException("Failed to fetch user insights summary");
        }
    }

    public Map<String, Object> calculateTotals(List<Document> insightsData) {
        Map<String, Object> out = zeroTotals();
        if (insightsData == null || insightsData.isEmpty()) return out;
        for (Document item : insightsData) {
            for (String k : List.of("totalNewUserRegistrations", "totalAttempts", "totalFailed", "totalCompleted", "totalAttemptsChats", "totalFailedChats", "failedAttemptsChats", "totalCompletedChats", "totalFreeChats", "totalPaidChats", "totalFirstChats", "totalRepeatChats", "totalAttemptsCalls", "totalFailedCalls", "failedAttemptsCalls", "totalCompletedCalls", "totalFreeCalls", "totalPaidCalls", "totalFirstCalls", "totalRepeatCalls", "totalTime", "totalTimeChats", "totalTimeFreeChat", "totalTimePaidChat", "totalTimeFirstChat", "totalTimeRepeatChats", "totalTimeCalls", "totalTimeFreeCalls", "totalTimePaidCalls", "totalTimeFirstCalls", "totalTimeRepeatCalls", "totalRecharge", "uniqueRechargeUsers", "totalSpending", "totalPaidChatSpending", "totalFirstPaidChatSpending", "totalRepeatPaidChatSpending", "totalPaidCallsSpending", "totalFirstPaidCallsSpending", "totalRepeatPaidCallsSpending")) out.put(k, n0(out.get(k)) + n0(item.get(k)));
        }
        double totalAttempts = n0(out.get("totalAttempts")); double totalPaidChats = n0(out.get("totalPaidChats")); double totalPaidCalls = n0(out.get("totalPaidCalls")); double totalFirstChats = n0(out.get("totalFirstChats")); double totalFirstCalls = n0(out.get("totalFirstCalls")); double totalRepeatChats = n0(out.get("totalRepeatChats")); double totalRepeatCalls = n0(out.get("totalRepeatCalls"));
        out.put("avgTime", totalAttempts > 0 ? n0(out.get("totalTime")) / totalAttempts : 0);
        out.put("avgTimeChats", n0(out.get("totalTimeChats")) > 0 ? n0(out.get("totalTimeChats")) / (n0(out.get("totalFreeChats")) + n0(out.get("totalPaidChats"))) : 0);
        out.put("avgTimeFreeChat", n0(out.get("totalFreeChats")) > 0 ? n0(out.get("totalTimeFreeChat")) / n0(out.get("totalFreeChats")) : 0);
        out.put("avgTimePaidChat", totalPaidChats > 0 ? n0(out.get("totalTimePaidChat")) / totalPaidChats : 0);
        out.put("avgTimeFirstChat", totalFirstChats > 0 ? n0(out.get("totalTimeFirstChat")) / totalFirstChats : 0);
        out.put("avgTimeRepeatChats", totalRepeatChats > 0 ? n0(out.get("totalTimeRepeatChats")) / totalRepeatChats : 0);
        out.put("avgTimeCalls", n0(out.get("totalAttemptsCalls")) > 0 ? n0(out.get("totalTimeCalls")) / n0(out.get("totalAttemptsCalls")) : 0);
        out.put("avgTimeFreeCalls", n0(out.get("totalFreeCalls")) > 0 ? n0(out.get("totalTimeFreeCalls")) / n0(out.get("totalFreeCalls")) : 0);
        out.put("avgTimePaidCalls", totalPaidCalls > 0 ? n0(out.get("totalTimePaidCalls")) / totalPaidCalls : 0);
        out.put("avgTimeFirstCalls", totalFirstCalls > 0 ? n0(out.get("totalTimeFirstCalls")) / totalFirstCalls : 0);
        out.put("avgTimeRepeatCalls", totalRepeatCalls > 0 ? n0(out.get("totalTimeRepeatCalls")) / totalRepeatCalls : 0);
        out.put("avgSpending", totalAttempts > 0 ? n0(out.get("totalSpending")) / totalAttempts : 0);
        out.put("avgPaidChatSpending", totalPaidChats > 0 ? n0(out.get("totalPaidChatSpending")) / totalPaidChats : 0);
        out.put("avgFirstPaidChatSpending", totalFirstChats > 0 ? n0(out.get("totalFirstPaidChatSpending")) / totalFirstChats : 0);
        out.put("avgRepeatPaidChatSpending", totalRepeatChats > 0 ? n0(out.get("totalRepeatPaidChatSpending")) / totalRepeatChats : 0);
        out.put("avgPaidCallsSpending", totalPaidCalls > 0 ? n0(out.get("totalPaidCallsSpending")) / totalPaidCalls : 0);
        out.put("avgFirstPaidCallsSpending", totalFirstCalls > 0 ? n0(out.get("totalFirstPaidCallsSpending")) / totalFirstCalls : 0);
        out.put("avgRepeatPaidCallsSpending", totalRepeatCalls > 0 ? n0(out.get("totalRepeatPaidCallsSpending")) / totalRepeatCalls : 0);
        return out;
    }

    private Map<String, Object> zeroTotals() {
        Map<String, Object> z = new LinkedHashMap<>();
        for (String k : List.of("totalAttempts", "totalFailed", "totalCompleted", "totalNewUserRegistrations", "totalAttemptsChats", "totalFailedChats", "failedAttemptsChats", "totalCompletedChats", "totalFreeChats", "totalPaidChats", "totalFirstChats", "totalRepeatChats", "totalAttemptsCalls", "totalFailedCalls", "failedAttemptsCalls", "totalCompletedCalls", "totalFreeCalls", "totalPaidCalls", "totalFirstCalls", "totalRepeatCalls", "totalTime", "avgTime", "totalTimeChats", "avgTimeChats", "totalTimeFreeChat", "avgTimeFreeChat", "totalTimePaidChat", "avgTimePaidChat", "totalTimeFirstChat", "avgTimeFirstChat", "totalTimeRepeatChats", "avgTimeRepeatChats", "totalTimeCalls", "avgTimeCalls", "totalTimeFreeCalls", "avgTimeFreeCalls", "totalTimePaidCalls", "avgTimePaidCalls", "totalTimeFirstCalls", "avgTimeFirstCalls", "totalTimeRepeatCalls", "avgTimeRepeatCalls", "totalRecharge", "uniqueRechargeUsers", "totalSpending", "avgSpending", "totalPaidChatSpending", "avgPaidChatSpending", "totalFirstPaidChatSpending", "avgFirstPaidChatSpending", "totalRepeatPaidChatSpending", "avgRepeatPaidChatSpending", "totalPaidCallsSpending", "avgPaidCallsSpending", "totalFirstPaidCallsSpending", "avgFirstPaidCallsSpending", "totalRepeatPaidCallsSpending", "avgRepeatPaidCallsSpending")) z.put(k, 0);
        return z;
    }

    Map<String, Object> getOverallStatistics(String startDate, String endDate) {
        try {
            Document dateFilter = new Document();
            if (truthy(startDate) && truthy(endDate)) dateFilter.put("createdAt", new Document("$gte", parseDate(startDate)).append("$lte", parseDate(endDate)));
            List<Document> waitlistStats = mongo.getCollection(Collections.WAITLISTS).aggregate(buildOverallWaitlistStatsPipeline(dateFilter)).into(new ArrayList<>());
            List<Document> spendingStats = mongo.getCollection(Collections.WAITLISTS).aggregate(buildSpendingStatsPipeline(dateFilter)).into(new ArrayList<>());
            Document waitlistMetrics = waitlistStats.isEmpty() ? new Document("totalFreeChatsCalls", 0).append("totalPaidChatsCalls", 0).append("totalFreeChatsTimesInSeconds", 0).append("totalPaidChatsTimesInSeconds", 0).append("uniqueUsers", List.of()) : waitlistStats.get(0);
            Document spendingMetrics = spendingStats.isEmpty() ? new Document("totalSpending", 0).append("uniqueUsers", List.of()) : spendingStats.get(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalFreeChatsCalls", waitlistMetrics.get("totalFreeChatsCalls"));
            result.put("totalPaidChatsCalls", waitlistMetrics.get("totalPaidChatsCalls"));
            result.put("totalTimeFreeChatCall", waitlistMetrics.get("totalFreeChatsTimesInSeconds"));
            result.put("totalTimePaidChatCall", waitlistMetrics.get("totalPaidChatsTimesInSeconds"));
            result.put("totalSpending", spendingMetrics.get("totalSpending"));
            LinkedHashSet<Object> unique = new LinkedHashSet<>();
            if (waitlistMetrics.get("uniqueUsers") instanceof List<?> l) unique.addAll(l);
            if (spendingMetrics.get("uniqueUsers") instanceof List<?> l) unique.addAll(l);
            result.put("totalUniqueUsers", unique.size());
            return result;
        } catch (Exception error) {
            throw new RuntimeException("Failed to fetch overall statistics");
        }
    }

    List<Document> buildOverallWaitlistStatsPipeline(Document dateFilter) {
        return List.of(new Document("$match", dateFilter), p("""
{"$group":{"_id":null,"totalAttempts":{"$sum":1},"totalCompleted":{"$sum":{"$cond":[{"$eq":["$status","completed"]},1,0]}},"totalProgress":{"$sum":{"$cond":[{"$eq":["$status","progress"]},1,0]}},"totalWaiting":{"$sum":{"$cond":[{"$eq":["$status","waiting"]},1,0]}},"totalCanceled":{"$sum":{"$cond":[{"$eq":["$status","canceled"]},1,0]}},"totalFreeChatsCalls":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$coupon.code","FREE5MINUTES"]}]},1,0]}},"totalPaidChatsCalls":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},1,0]}},"totalFreeChatsTimesInSeconds":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$eq":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"totalPaidChatsTimesInSeconds":{"$sum":{"$cond":[{"$and":[{"$eq":["$status","completed"]},{"$ne":["$coupon.code","FREE5MINUTES"]}]},{"$ifNull":["$onCompletion.callDurationInSeconds",0]},0]}},"uniqueUsers":{"$addToSet":"$user_id"}}}
"""));
    }

    List<Document> buildSpendingStatsPipeline(Document dateFilter) {
        return List.of(new Document("$match", dateFilter), p("{\"$group\":{\"_id\":null,\"totalSpending\":{\"$sum\":{\"$ifNull\":[\"$onCompletion.amountToDeduct\",0]}},\"uniqueUsers\":{\"$addToSet\":\"$user_id\"}}}"));
    }

    public Map<String, Object> getUsersWithOneCompletedConsultation(Map<String, String> query) {
        // FAITHFUL(node-quirk): rest-apis/modules/admin/user-insights.js:52 calls a method not implemented by user-insights-service.js, so the route always returns this TypeError message.
        throw new RuntimeException("UserInsightsService.getUsersWithOneCompletedConsultation is not a function");
    }

    private static Document p(String json) { return Document.parse(json); }
    private static String text(Map<String, String> q, String key, String def) { return q == null || q.get(key) == null ? def : q.get(key); }
    private static boolean truthy(String s) { return s != null && !s.isEmpty(); }
    private static int parseInt(String s, int def) { try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; } }
    private static Date parseDate(String s) { try { return Date.from(Instant.parse(s)); } catch (Exception e) { return Date.from(LocalDate.parse(s).atStartOfDay(ZoneId.of("UTC")).toInstant()); } }
    private static double num(Object v) { if (v == null) return Double.NaN; if (v instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return Double.NaN; } }
    private static double n0(Object v) { double n = num(v); return Double.isNaN(n) ? 0 : n; }
    private static Object jsOr(Object value, Object fallback) { if (value == null) return fallback; if (value instanceof Number n && n.doubleValue() == 0) return fallback; if (value instanceof String s && s.isEmpty()) return fallback; return value; }
    private static double subRaw(Object a, Object b) { return num(a) - num(b); }
    private static double addRaw(Object a, Object b) { return num(a) + num(b); }
    private static double jsOr0(double v) { return Double.isNaN(v) || v == 0 ? 0 : v; }
    private static double subOr0(Object a, Object b) { return jsOr0(subRaw(a, b)); }
    private static double max0Or0(double v) { return Double.isNaN(v) ? 0 : Math.max(0, v); }
    private static double divOr0(Object a, Object b) { double v = num(a) / num(b); return Double.isNaN(v) || v == 0 ? 0 : v; }
    private static String formatMoment(Object value) {
        Instant instant = value instanceof Date d ? d.toInstant() : value instanceof Instant i ? i : Instant.now();
        ZonedDateTime z = instant.atZone(ZoneId.systemDefault());
        int hour = z.getHour() % 12; if (hour == 0) hour = 12;
        return String.format(Locale.ENGLISH, "%02d %s %04d, %02d:%02d %s", z.getDayOfMonth(), MONTHS[z.getMonthValue() - 1], z.getYear(), hour, z.getMinute(), z.getHour() < 12 ? "AM" : "PM");
    }
}
