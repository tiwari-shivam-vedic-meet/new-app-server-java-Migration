package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Native Java port of Node rest-apis/modules/admin/consultant-insights.js. */
@Service
public class AdminConsultantInsightsService {
    private static final String FREE = "FREE5MINUTES";
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final Set<String> SORT_FIELDS = Set.of("consultantName", "totalAttemptsForConsultation", "totalPaidChatCallSpending", "completionRate", "paidConsultationConversionRate", "consultantType");
    private static final Set<String> TIME_RANGES = Set.of("today", "last7days", "last30days", "last60days", "last90days", "custom");
    private static final Set<String> GENDERS = Set.of("male", "female", "transgender", "");

    private final MongoTemplate mongo;
    @SuppressWarnings("unused") private final AdminMongoSupport support;

    public AdminConsultantInsightsService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> getConsultantInsights(Map<String, String> query) {
        validateInsights(query);
        try {
            String page = v(query, "page", "1"), pageSize = v(query, "pageSize", "10"), search = v(query, "search", "");
            String startDate = v(query, "startDate", null), endDate = v(query, "endDate", null);
            String sortField = v(query, "sortField", "consultantName"), sortDirection = v(query, "sortDirection", "asc");
            String timeRange = v(query, "timeRange", null);
            // FAITHFUL(node-quirk): consultant-insights-service.js:34 dateFilter is computed but unused.
            buildDateFilter(startDate, endDate, timeRange);
            Map<String, String> filters = new LinkedHashMap<>();
            for (String k : List.of("timeRange", "consultantType", "problemType", "consultantGender", "userGender")) filters.put(k, v(query, k, null));
            int pageNum = Integer.parseInt(page), limit = Integer.parseInt(pageSize), skip = (pageNum - 1) * Integer.parseInt(pageSize);
            List<Map<String, Object>> sortedData = new ArrayList<>(getConsultantWiseInsights(startDate, endDate, search, filters));
            if (sortField != null && sortDirection != null) sortedData.sort((a, b) -> nodeCompare(a.get(sortField), b.get(sortField), sortDirection));
            int total = sortedData.size(), to = Math.min(skip + limit, total);
            List<Map<String, Object>> pageData = skip >= total ? List.of() : new ArrayList<>(sortedData.subList(skip, to));
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("totalDocuments", total);
            pagination.put("currentPage", Integer.parseInt(page));
            pagination.put("totalPages", (int)Math.ceil(total / (double)limit));
            pagination.put("hasNextPage", Integer.parseInt(page) * limit < total);
            pagination.put("hasPrevPage", Integer.parseInt(page) > 1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("consultations", pageData);
            result.put("pagination", pagination);
            result.put("overallTotalDataConsultation", overallTotals(sortedData));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch consultant insights");
        }
    }

    public Map<String, Object> getConsultantInsightsSummary(Map<String, String> query) {
        validateSummary(query);
        try {
            // FAITHFUL(node-quirk): consultant-insights-service.js:1164 summary schema accepts timeRange but service ignores it.
            long totalConsultants = mongo.getCollection(Collections.CONSULTANTS).countDocuments();
            long activeConsultants = mongo.getCollection(Collections.CONSULTANTS).countDocuments(new Document("isActive", "online"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalConsultants", totalConsultants);
            result.put("activeConsultants", activeConsultants);
            result.putAll(getOverallConsultantStatistics(v(query, "startDate", null), v(query, "endDate", null)));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch consultant insights summary");
        }
    }

    Document buildDateFilter(String startDate, String endDate, String timeRange) {
        Document f = new Document();
        if (present(timeRange) && !"custom".equals(timeRange)) {
            try { f.append("createdAt", range(timeRange)); } catch (Exception ignored) { }
        } else if (present(startDate) && present(endDate)) {
            f.append("createdAt", new Document("$gte", jsDate(startDate)).append("$lte", jsDate(endDate)));
        }
        return f;
    }

    List<Map<String, Object>> getConsultantWiseInsights(String startDate, String endDate, String search, Map<String, String> filters) {
        try {
            Document wf = new Document(buildDateFilter(startDate, endDate, filters == null ? null : filters.get("timeRange")));
            if (filters != null) {
                if (present(filters.get("consultantType"))) wf.append("consultant.primarySkills", new Document("$in", List.of(filters.get("consultantType"))));
                if (present(filters.get("problemType"))) wf.append("request_form.concern", filters.get("problemType"));
                if (present(filters.get("consultantGender"))) wf.append("consultant.details.gender", filters.get("consultantGender"));
                if (present(filters.get("userGender"))) wf.append("user.details.gender", filters.get("userGender"));
            }
            List<Document> docs;
            try { docs = mongo.getCollection(Collections.WAITLISTS).aggregate(consultantWisePipeline(wf)).into(new ArrayList<>()); }
            catch (Exception lookupError) {
                // FAITHFUL(node-quirk): consultant-insights-service.js:896 waitlist aggregation errors return an empty list.
                return List.of();
            }
            List<Map<String, Object>> merged = mergeConsultantData(docs);
            if (!present(search)) return merged;
            String n = search.toLowerCase();
            return merged.stream().filter(i -> matches(i, n, search)).toList();
        } catch (Exception e) { throw new IllegalStateException("Failed to fetch consultant-wise insights"); }
    }

    private Map<String, Object> getOverallConsultantStatistics(String startDate, String endDate) {
        try {
            Document df = new Document();
            if (present(startDate) && present(endDate)) df.append("createdAt", new Document("$gte", jsDate(startDate)).append("$lte", jsDate(endDate)));
            List<Document> wl = mongo.getCollection(Collections.WAITLISTS).aggregate(waitlistStatsPipeline(df)).into(new ArrayList<>());
            List<Document> tx = mongo.getCollection(Collections.WAITLISTS).aggregate(transactionStatsPipeline(df)).into(new ArrayList<>());
            Document w = wl.isEmpty() ? new Document("totalAttempts",0).append("totalCompleted",0).append("totalFailed",0).append("totalFreeChats",0).append("totalPaidChats",0).append("totalFreeCalls",0).append("totalPaidCalls",0).append("totalFreeChatsTimesInSeconds",0).append("totalPaidChatsTimesInSeconds",0).append("totalFreeCallsTimesInSeconds",0).append("totalPaidCallsTimesInSeconds",0).append("uniqueConsultants",List.of()).append("uniqueUsers",List.of()) : wl.get(0);
            Document t = tx.isEmpty() ? new Document("totalTransactions",0).append("totalChatRevenue",0).append("totalCallRevenue",0).append("uniqueConsultants",List.of()) : tx.get(0);
            Map<String, Object> r = new LinkedHashMap<>();
            for (String k : List.of("totalAttempts","totalCompleted","totalFailed","totalFreeChats","totalPaidChats","totalFreeCalls","totalPaidCalls","totalFreeChatsTimesInSeconds","totalPaidChatsTimesInSeconds","totalFreeCallsTimesInSeconds","totalPaidCallsTimesInSeconds")) r.put(k, w.get(k));
            r.put("totalRevenue", num(t.get("totalChatRevenue")) + num(t.get("totalCallRevenue")));
            r.put("totalChatRevenue", fb(t.get("totalChatRevenue"), 0));
            r.put("totalCallRevenue", fb(t.get("totalCallRevenue"), 0));
            r.put("totalUniqueConsultants", (int)java.util.stream.Stream.concat(coll(w.get("uniqueConsultants")).stream(), coll(t.get("uniqueConsultants")).stream()).filter(Objects::nonNull).distinct().count());
            r.put("totalUniqueUsers", coll(w.get("uniqueUsers")).size());
            return r;
        } catch (Exception e) { throw new IllegalStateException("Failed to fetch overall consultant statistics"); }
    }

    private List<Map<String, Object>> mergeConsultantData(List<Document> waitlistData) {
        Map<Object, Map<String, Object>> mm = new LinkedHashMap<>();
        for (Document item : waitlistData) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("_id", item.get("_id")); m.put("consultantName", truthy(item.get("consultantName"), "Unknown Consultant"));
            m.put("consultantType", truthy(item.get("consultantType"), List.of())); m.put("consultantGender", truthy(item.get("consultantGender"), "Not Specified"));
            m.put("consultantEmail", truthy(item.get("consultantEmail"), "")); m.put("consultantPhone", truthy(item.get("consultantPhone"), ""));
            for (String k : List.of("totalAttemptsForConsultation","totalAttemptChat","totalAttemptCall","totalFreeChat","totalPaidChat","totalFreeCall","totalPaidCall","totalCompleted","totalFailed","totalFreeChatTime","totalPaidChatTime","totalFreeCallTime","totalPaidCallTime","uniqueUsers","uniquePaidChatUsers","uniquePaidCallUsers","totalUniqueFirstTimePaidChats","totalUniqueFirstTimePaidCalls","uniqueFirstTimePaidChatUsers","uniqueFirstTimePaidCallUsers","totalPaidChatRevenue","totalPaidCallRevenue")) m.put(k, fb(item.get(k), 0));
            m.put("totalFirstTimePaidChatTime", fb(item.get("totalUniqueFirstTimePaidChats"), 0));
            // FAITHFUL(node-quirk): consultant-insights-service.js:959 seeds chat average from call user count before recalculation.
            m.put("averageFirstTimePaidChatTime", fb(item.get("uniqueFirstTimePaidCallUsers"), 0));
            m.put("totalRepeatChatTime", jsOr(num(item.get("totalPaidChatTime")) - num(item.get("totalUniqueFirstTimePaidChats")), 0));
            m.put("averageRepeatChatTime", fb(item.get("averageRepeatChatTime"), 0));
            m.put("totalFirstTimePaidCallTime", fb(item.get("totalFirstTimePaidCallTime"), 0));
            m.put("averageFirstTimePaidCallTime", fb(item.get("averageRepeatCallTime"), 0));
            m.put("totalRepeatCallTime", jsOr(num(item.get("totalPaidCallTime")) - num(item.get("totalUniqueFirstTimePaidCalls")), 0));
            m.put("averageRepeatCallTime", fb(item.get("averageRepeatCallTime"), 0));
            m.put("usersWithSingleChatSession", item.get("usersWithSingleChatSession")); m.put("usersWithSingleCallSession", item.get("usersWithSingleCallSession"));
            mm.put(item.get("_id"), m);
        }
        List<Map<String, Object>> outList = new ArrayList<>();
        for (Map<String, Object> item : mm.values()) {
            double attempts=num(item.get("totalAttemptsForConsultation")), freeChats=num(item.get("totalFreeChat")), paidChats=num(item.get("totalPaidChat")), freeCalls=num(item.get("totalFreeCall")), paidCalls=num(item.get("totalPaidCall"));
            double firstChat=num(item.get("uniquePaidChatUsers")), repeatChat=Math.max(0, paidChats-firstChat), firstCall=num(item.get("uniquePaidCallUsers")), repeatCall=Math.max(0, paidCalls-firstCall);
            double completed=num(item.get("totalCompleted")), failed=num(item.get("totalFailed"));
            double uFirstChat=num(item.get("totalUniqueFirstTimePaidChats")), uFirstCall=num(item.get("totalUniqueFirstTimePaidCalls"));
            double completionRate=attempts>0?(completed/attempts)*100:0, freeChatPct=attempts>0?(freeChats/attempts)*100:0, paidChatPct=attempts>0?(paidChats/attempts)*100:0;
            double firstChatPct=paidChats>0?(firstChat/paidChats)*100:0, repeatChatPct=paidChats>0?(repeatChat/paidChats)*100:0, freeCallPct=attempts>0?(freeCalls/attempts)*100:0, paidCallPct=attempts>0?(paidCalls/attempts)*100:0;
            double firstCallPct=paidCalls>0?(firstCall/paidCalls)*100:0, repeatCallPct=paidCalls>0?(repeatCall/paidCalls)*100:0;
            double freeChatTime=num(item.get("totalFreeChatTime")), paidChatTime=num(item.get("totalPaidChatTime")), freeCallTime=num(item.get("totalFreeCallTime")), paidCallTime=num(item.get("totalPaidCallTime"));
            double avgFreeChat=freeChats>0?freeChatTime/freeChats:0, avgPaidChat=paidChats>0?paidChatTime/paidChats:0, avgFreeCall=freeCalls>0?freeCallTime/freeCalls:0, avgPaidCall=paidCalls>0?paidCallTime/paidCalls:0;
            double firstChatTime=uFirstChat, avgFirstChat=firstChat>0?firstChatTime/firstChat:0, repeatChatTime=jsOr(paidChatTime-uFirstChat,0), avgRepeatChat=repeatChat>0?repeatChatTime/repeatChat:0;
            double firstCallTime=uFirstCall, avgFirstCall=firstCall>0?firstCallTime/firstCall:0, repeatCallTime=jsOr(paidCallTime-uFirstCall,0), avgRepeatCall=repeatCall>0?repeatCallTime/repeatCall:0;
            double chatRev=num(item.get("totalPaidChatRevenue")), callRev=num(item.get("totalPaidCallRevenue"));
            double avgChatRev=paidChats>0?chatRev/paidChats:0, avgPaidChatRev=paidChats>0?chatRev/paidChats:0, avgCallRev=paidCalls>0?callRev/paidCalls:0, avgPaidCallRev=paidCalls>0?callRev/paidCalls:0;
            double firstChatRev=firstChat>0?round2(chatRev*(firstChat/paidChats)):0, avgFirstChatRev=firstChat>0?firstChatRev/firstChat:0, repeatChatRev=repeatChat>0?round2(chatRev*(repeatChat/paidChats)):0, avgRepeatChatRev=repeatChat>0?repeatChatRev/repeatChat:0;
            double avgFirstCallRev=firstCall>0?callRev/firstCall:0, avgRepeatCallRev=repeatCall>0?callRev/repeatCall:0;
            Map<String,Object> o = new LinkedHashMap<>();
            o.put("_id", item.get("_id")); o.put("consultantName", item.get("consultantName")); o.put("consultantType", item.get("consultantType")); o.put("consultantGender", item.get("consultantGender")); o.put("consultantEmail", item.get("consultantEmail")); o.put("consultantPhone", item.get("consultantPhone"));
            o.put("totalAttemptChatCall", attempts); o.put("totalAttemptChat", fb(item.get("totalAttemptChat"),0)); o.put("totalAttemptCall", fb(item.get("totalAttemptCall"),0)); o.put("totalFailedChatCall", failed); o.put("totalCompletedChatCall", completed); o.put("totalFreeChat", freeChats); o.put("totalPaidChat", paidChats); o.put("totalFirstTimeChat", firstChat); o.put("totalRepeatChat", repeatChat); o.put("totalFreeCall", freeCalls); o.put("totalPaidCall", paidCalls); o.put("totalFirstTimeCall", firstCall);
            o.put("totalUniqueFirstTimePaidChats", uFirstChat); o.put("totalUniqueFirstTimePaidCalls", uFirstCall); o.put("uniqueFirstTimePaidChatUsers", item.get("uniqueFirstTimePaidChatUsers")); o.put("uniqueFirstTimePaidCallUsers", item.get("uniqueFirstTimePaidCallUsers")); o.put("usersWithSingleChatSession", item.get("usersWithSingleChatSession")); o.put("usersWithSingleCallSession", item.get("usersWithSingleCallSession"));
            o.put("completionRatePercentage", round2(completionRate)); o.put("completionRateNumber", completed); o.put("freeChatPercentage", round2(freeChatPct)); o.put("paidChatPercentage", round2(paidChatPct)); o.put("firstTimePaidChatPercentage", round2(firstChatPct)); o.put("repeatPaidChatPercentage", round2(repeatChatPct)); o.put("freeCallPercentage", round2(freeCallPct)); o.put("paidCallPercentage", round2(paidCallPct)); o.put("firstTimePaidCallPercentage", round2(firstCallPct)); o.put("repeatPaidCallPercentage", round2(repeatCallPct));
            o.put("totalFreeChatTime", freeChatTime); o.put("totalPaidChatTime", paidChatTime); o.put("averageFreeChatTime", round2(avgFreeChat)); o.put("averagePaidChatTime", round2(avgPaidChat)); o.put("totalFirstTimePaidChatTime", uFirstChat); o.put("averageFirstTimePaidChatTime", round2(avgFirstChat)); o.put("totalRepeatChatTime", repeatChatTime); o.put("averageRepeatChatTime", round2(avgRepeatChat)); o.put("totalFreeCallTime", freeCallTime); o.put("totalPaidCallTime", paidCallTime); o.put("averageFreeCallTime", round2(avgFreeCall)); o.put("averagePaidCallTime", round2(avgPaidCall)); o.put("totalFirstTimePaidCallTime", firstCallTime); o.put("averageFirstTimePaidCallTime", round2(avgFirstCall)); o.put("totalRepeatCallTime", repeatCallTime); o.put("averageRepeatCallTime", round2(avgRepeatCall));
            o.put("totalChatRevenue", chatRev); o.put("totalPaidChatRevenue", chatRev); o.put("totalFirstTimePaidChatRevenue", firstChatRev); o.put("totalRepeatPaidChatRevenue", repeatChatRev); o.put("averageChatRevenue", round2(avgChatRev)); o.put("averagePaidChatRevenue", round2(avgPaidChatRev)); o.put("averageFirstTimePaidChatRevenue", round2(avgFirstChatRev)); o.put("averageRepeatPaidChatRevenue", round2(avgRepeatChatRev)); o.put("totalCallRevenue", callRev); o.put("totalPaidCallRevenue", callRev);
            // FAITHFUL(node-quirk): consultant-insights-service.js:1141 total first-time call revenue returns the average value.
            o.put("totalFirstTimePaidCallRevenue", avgFirstCallRev);
            // FAITHFUL(node-quirk): consultant-insights-service.js:1142 total repeat call revenue returns the average value.
            o.put("totalRepeatPaidCallRevenue", avgRepeatCallRev);
            o.put("averageCallRevenue", round2(avgCallRev)); o.put("averagePaidCallRevenue", round2(avgPaidCallRev)); o.put("averageFirstTimePaidCallRevenue", avgFirstCallRev); o.put("averageRepeatPaidCallRevenue", avgRepeatCallRev); o.put("totalRepeatCall", repeatCall);
            o.put("totalFirstTimePaidChatTime", firstChatTime); o.put("averageFirstTimePaidChatTime", avgFirstChat); o.put("totalRepeatChatTime", repeatChatTime); o.put("averageRepeatChatTime", avgRepeatChat); o.put("totalRepeatCallTime", repeatCallTime); o.put("averageFirstTimePaidCallTime", avgFirstCall); o.put("averageRepeatCallTime", avgRepeatCall);
            outList.add(o);
        }
        return outList;
    }

    private Map<String, Object> overallTotals(List<Map<String, Object>> data) {
        Map<String,Object> a = new LinkedHashMap<>();
        for (String k : List.of("totalAttemptChatCall","totalFailedChatCall","totalCompletedChatCall","totalAttemptChat","totalFailedChat","totalCompletedChat","totalFreeChat","totalPaidChat","totalFirstTimeChat","totalRepeatChat","totalAttemptCall","totalFailedCall","totalCompletedCall","totalFreeCall","totalPaidCall","totalFirstTimeCall","totalRepeatCall","completionRateNumber","freeChatPercentage","paidChatPercentage","firstTimePaidChatPercentage","repeatPaidChatPercentage","freeCallPercentage","paidCallPercentage","firstTimePaidCallPercentage","repeatPaidCallPercentage","totalFreeChatTime","totalPaidChatTime","averageFreeChatTime","averagePaidChatTime","totalRepeatChatTime","averageRepeatChatTime","totalFreeCallTime","totalPaidCallTime","averageFreeCallTime","averagePaidCallTime","totalRepeatCallTime","averageRepeatCallTime","totalFirstTimePaidChatTime","averageFirstTimePaidChatTime","totalFirstTimePaidCallTime","averageFirstTimePaidCallTime","totalChatRevenue","totalPaidChatRevenue","totalFirstTimePaidChatRevenue","totalRepeatPaidChatRevenue","averageChatRevenue","averagePaidChatRevenue","averageFirstTimePaidChatRevenue","averageRepeatPaidChatRevenue","totalCallRevenue","totalPaidCallRevenue","totalFirstTimePaidCallRevenue","totalRepeatPaidCallRevenue","averageCallRevenue","averagePaidCallRevenue","averageFirstTimePaidCallRevenue","averageRepeatPaidCallRevenue")) a.put(k, 0.0);
        for (Map<String,Object> c : data) {
            add(a,"totalAttemptChatCall",c,"totalAttemptChatCall"); add(a,"totalFailedChatCall",c,"totalFailedChatCall"); add(a,"totalCompletedChatCall",c,"totalCompletedChatCall"); add(a,"totalAttemptChat",c,"totalAttemptChat");
            // FAITHFUL(node-quirk): consultant-insights-service.js:83 chat failure total reuses totalFailedChatCall.
            add(a,"totalFailedChat",c,"totalFailedChatCall"); a.put("totalCompletedChat", num(a.get("totalCompletedChat"))+num(c.get("totalFreeChat"))+num(c.get("totalPaidChat"))); add(a,"totalFreeChat",c,"totalFreeChat"); add(a,"totalPaidChat",c,"totalPaidChat"); add(a,"totalFirstTimeChat",c,"totalFirstTimeChat"); add(a,"totalRepeatChat",c,"totalRepeatChat"); add(a,"totalAttemptCall",c,"totalAttemptCall");
            // FAITHFUL(node-quirk): consultant-insights-service.js:92 call failure total reuses totalFailedChatCall.
            add(a,"totalFailedCall",c,"totalFailedChatCall"); a.put("totalCompletedCall", num(a.get("totalCompletedCall"))+num(c.get("totalFreeCall"))+num(c.get("totalPaidCall")));
            for (String k : a.keySet()) if (!List.of("totalAttemptChatCall","totalFailedChatCall","totalCompletedChatCall","totalAttemptChat","totalFailedChat","totalCompletedChat","totalFreeChat","totalPaidChat","totalFirstTimeChat","totalRepeatChat","totalAttemptCall","totalFailedCall","totalCompletedCall").contains(k)) add(a,k,c,k);
        }
        double attempts=num(a.get("totalAttemptChatCall")), completed=num(a.get("totalCompletedChatCall"));
        double totalTime=num(a.get("totalFreeChatTime"))+num(a.get("totalPaidChatTime"))+num(a.get("totalFreeCallTime"))+num(a.get("totalPaidCallTime"));
        double totalRev=num(a.get("totalChatRevenue"))+num(a.get("totalCallRevenue"));
        a.put("totalTimeChatCall", round2(totalTime)); a.put("avgTimeChatCall", attempts>0?round2(totalTime/attempts):0); a.put("totalRevenueChatCall", round2(totalRev)); a.put("avgRevenueChatCall", attempts>0?round2(totalRev/attempts):0); a.put("totalAttemptChat", a.get("totalAttemptChat")); a.put("totalAttemptCall", a.get("totalAttemptCall"));
        a.put("totalFirstTimePaidChatTime", round2(num(a.get("totalFirstTimePaidChatTime")))); a.put("averageFirstTimePaidChatTime", round2(num(a.get("averageFirstTimePaidChatTime")))); a.put("totalRepeatChatTime", a.get("totalRepeatChatTime")); a.put("averageRepeatChatTime", num(a.get("totalRepeatChat"))>0?round2(num(a.get("totalPaidChatTime"))/num(a.get("totalPaidChat"))):0);
        a.put("totalFirstTimePaidCallTime", round2(num(a.get("totalFirstTimePaidCallTime")))); a.put("averageFirstTimePaidCallTime", round2(num(a.get("averageFirstTimePaidCallTime")))); a.put("totalRepeatCallTime", a.get("totalRepeatCallTime")); a.put("averageRepeatCallTime", num(a.get("totalRepeatCall"))>0?round2(num(a.get("totalPaidCallTime"))/num(a.get("totalRepeatCall"))):0);
        // FAITHFUL(node-quirk): consultant-insights-service.js:284 toFixed result is a string when attempts exist, but number 0 otherwise.
        a.put("completionRatePercentage", attempts>0 ? fixed2((completed/attempts)*100) : 0);
        return a;
    }

    static List<Document> consultantWisePipeline(Document wf) {
        List<Document> p = new ArrayList<>();
        p.add(Document.parse("{\"$sort\":{\"createdAt\":-1}}"));
        p.add(Document.parse("{\"$lookup\":{\"from\":\"consultants\",\"let\":{\"consultantId\":\"$consultant_id\"},\"pipeline\":[{\"$match\":{\"$expr\":{\"$eq\":[{\"$toObjectId\":\"$$consultantId\"},\"$_id\"]}}}],\"as\":\"consultant\"}}"));
        p.add(Document.parse("{\"$unwind\":{\"path\":\"$consultant\",\"preserveNullAndEmptyArrays\":true}}"));
        p.add(Document.parse("{\"$lookup\":{\"from\":\"users\",\"let\":{\"userId\":\"$user_id\"},\"pipeline\":[{\"$match\":{\"$expr\":{\"$eq\":[{\"$toObjectId\":\"$$userId\"},\"$_id\"]}}}],\"as\":\"user\"}}"));
        p.add(Document.parse("{\"$unwind\":{\"path\":\"$user\",\"preserveNullAndEmptyArrays\":true}}"));
        p.add(new Document("$match", wf == null ? new Document() : wf));
        p.add(Document.parse(groupJson()));
        p.add(Document.parse("{\"$addFields\":{\"uniqueFirstTimePaidChatUsers\":{\"$map\":{\"input\":{\"$setUnion\":[\"$allPaidChatSessions.userId\",[]]},\"as\":\"uid\",\"in\":{\"$first\":{\"$filter\":{\"input\":\"$allPaidChatSessions\",\"as\":\"u\",\"cond\":{\"$eq\":[\"$$u.userId\",\"$$uid\"]}}}}}},\"uniqueFirstTimePaidCallUsers\":{\"$map\":{\"input\":{\"$setUnion\":[\"$allPaidCallSessions.userId\",[]]},\"as\":\"uid\",\"in\":{\"$first\":{\"$filter\":{\"input\":\"$allPaidCallSessions\",\"as\":\"u\",\"cond\":{\"$eq\":[\"$$u.userId\",\"$$uid\"]}}}}}}}}"));
        p.add(Document.parse("{\"$addFields\":{\"totalUniqueFirstTimePaidChats\":{\"$reduce\":{\"input\":\"$uniqueFirstTimePaidChatUsers\",\"initialValue\":0,\"in\":{\"$add\":[\"$$value\",\"$$this.duration\"]}}},\"totalUniqueFirstTimePaidCalls\":{\"$reduce\":{\"input\":\"$uniqueFirstTimePaidCallUsers\",\"initialValue\":0,\"in\":{\"$add\":[\"$$value\",\"$$this.duration\"]}}},\"usersWithSingleChatSession\":{\"$filter\":{\"input\":\"$allPaidChatSessions\",\"as\":\"session\",\"cond\":{\"$eq\":[{\"$size\":{\"$filter\":{\"input\":\"$allPaidChatSessions\",\"cond\":{\"$eq\":[\"$$this.userId\",\"$$session.userId\"]}}}},1]}}},\"usersWithSingleCallSession\":{\"$filter\":{\"input\":\"$allPaidCallSessions\",\"as\":\"session\",\"cond\":{\"$eq\":[{\"$size\":{\"$filter\":{\"input\":\"$allPaidCallSessions\",\"cond\":{\"$eq\":[\"$$this.userId\",\"$$session.userId\"]}}}},1]}}}}}"));
        p.add(Document.parse("{\"$project\":{\"_id\":1,\"consultantName\":1,\"consultantType\":1,\"consultantGender\":1,\"consultantEmail\":1,\"consultantPhone\":1,\"totalAttemptsForConsultation\":1,\"totalAttemptChat\":1,\"totalAttemptCall\":1,\"totalFreeChat\":1,\"totalPaidChat\":1,\"totalFreeCall\":1,\"totalPaidCall\":1,\"totalCompleted\":1,\"totalFailed\":1,\"totalFreeChatTime\":1,\"totalPaidChatTime\":1,\"totalFreeCallTime\":1,\"totalPaidCallTime\":1,\"totalPaidChatRevenue\":1,\"totalPaidCallRevenue\":1,\"uniqueUsers\":{\"$size\":\"$uniqueUsers\"},\"uniquePaidChatUsers\":{\"$size\":{\"$filter\":{\"input\":\"$uniquePaidChatUsers\",\"cond\":{\"$ne\":[\"$$this\",null]}}}},\"uniquePaidCallUsers\":{\"$size\":{\"$filter\":{\"input\":\"$uniquePaidCallUsers\",\"cond\":{\"$ne\":[\"$$this\",null]}}}},\"totalUniqueFirstTimePaidChats\":1,\"totalUniqueFirstTimePaidCalls\":1,\"uniqueFirstTimePaidChatUsers\":{\"$size\":\"$usersWithSingleChatSession\"},\"uniqueFirstTimePaidCallUsers\":{\"$size\":\"$usersWithSingleCallSession\"},\"usersWithSingleChatSession\":1,\"usersWithSingleCallSession\":1}}"));
        return p;
    }

    private static String groupJson() {
        String c = FREE;
        String condChatFree = "{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$eq\":[\"$session_info.mode\",\"chat\"]},{\"$eq\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\""+c+"\"]}]}";
        String condChatPaid = "{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$eq\":[\"$session_info.mode\",\"chat\"]},{\"$ne\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\""+c+"\"]}]}";
        String condCallFreeIfNull = "{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$eq\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\""+c+"\"]}]}";
        String condCallPaid = "{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$ne\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\""+c+"\"]}]}";
        String condCallFreeNoIfNull = "{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$eq\":[\"$coupon.code\",\""+c+"\"]}]}";
        return "{\"$group\":{\"_id\":\"$consultant_id\",\"consultantName\":{\"$first\":\"$consultant.accountName\"},\"consultantType\":{\"$first\":\"$consultant.primarySkills\"},\"consultantGender\":{\"$first\":\"$consultant.details.gender\"},\"consultantEmail\":{\"$first\":\"$consultant.details.email\"},\"consultantPhone\":{\"$first\":\"$consultant.details.phone\"},\"totalAttemptsForConsultation\":{\"$sum\":1},\"totalAttemptChat\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$session_info.mode\",\"chat\"]},1,0]}},\"totalAttemptCall\":{\"$sum\":{\"$cond\":[{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},1,0]}},"+
        jSum("totalFreeChat",condChatFree,1)+","+jSum("totalPaidChat",condChatPaid,1)+","+jSum("totalFreeCall",condCallFreeNoIfNull,1)+","+jSum("totalPaidCall",condCallPaid,1)+",\"totalCompleted\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$status\",\"completed\"]},1,0]}},\"totalFailed\":{\"$sum\":{\"$cond\":[{\"$in\":[\"$status\",[\"canceled\",\"missed\"]]},1,0]}},"+
        jSum("totalFreeChatTime",condChatFree,"{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}")+","+jSum("totalPaidChatTime",condChatPaid,"{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}")+","+jSum("totalFreeCallTime",condCallFreeIfNull,"{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}")+","+jSum("totalPaidCallTime",condCallPaid,"{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}")+","+
        "\"uniqueUsers\":{\"$addToSet\":\"$user_id\"},\"uniquePaidChatUsers\":{\"$addToSet\":{\"$cond\":["+condChatPaid+",\"$user_id\",\"$$REMOVE\"]}},\"uniquePaidCallUsers\":{\"$addToSet\":{\"$cond\":["+condCallPaid+",\"$user_id\",\"$$REMOVE\"]}},"+
        "\"allPaidChatSessions\":{\"$push\":{\"$cond\":[{\"$and\":[{\"$eq\":[\"$session_info.mode\",\"chat\"]},{\"$eq\":[\"$status\",\"completed\"]},{\"$ne\":[\"$coupon.code\",\""+c+"\"]}]},{\"userId\":\"$user_id\",\"duration\":{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}},\"$$REMOVE\"]}},\"allPaidCallSessions\":{\"$push\":{\"$cond\":[{\"$and\":[{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$eq\":[\"$status\",\"completed\"]},{\"$ne\":[\"$coupon.code\",\""+c+"\"]}]},{\"userId\":\"$user_id\",\"duration\":{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}},\"$$REMOVE\"]}},"+
        jSum("totalPaidChatRevenue",condChatPaid,"{\"$ifNull\":[\"$onCompletion.consultantAmount\",0]}")+","+jSum("totalPaidCallRevenue",condCallPaid,"{\"$ifNull\":[\"$onCompletion.consultantAmount\",0]}")+"}}";
    }

    private static String jSum(String name, String cond, int val) { return "\""+name+"\":{\"$sum\":{\"$cond\":["+cond+","+val+",0]}}"; }
    private static String jSum(String name, String cond, String val) { return "\""+name+"\":{\"$sum\":{\"$cond\":["+cond+","+val+",0]}}"; }

    static List<Document> waitlistStatsPipeline(Document df) {
        String cf="{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$eq\":[\"$session_info.mode\",\"chat\"]},{\"$eq\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\"FREE5MINUTES\"]}]}", cp="{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$eq\":[\"$session_info.mode\",\"chat\"]},{\"$ne\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\"FREE5MINUTES\"]}]}", kf="{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$eq\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\"FREE5MINUTES\"]}]}", kp="{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$ne\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\"FREE5MINUTES\"]}]}";
        String dur="{\"$ifNull\":[\"$onCompletion.callDurationInSeconds\",0]}";
        String g="{\"$group\":{\"_id\":null,\"totalAttempts\":{\"$sum\":1},\"totalAttemptChat\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$session_info.mode\",\"chat\"]},1,0]}},\"totalAttemptCall\":{\"$sum\":{\"$cond\":[{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},1,0]}},\"totalCompleted\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$status\",\"completed\"]},1,0]}},\"totalFailed\":{\"$sum\":{\"$cond\":[{\"$in\":[\"$status\",[\"canceled\",\"missed\"]]},1,0]}},"+jSum("totalFreeChats",cf,1)+","+jSum("totalPaidChats",cp,1)+","+jSum("totalFreeCalls",kf,1)+","+jSum("totalPaidCalls",kp,1)+","+jSum("totalFreeChatsTimesInSeconds",cf,dur)+","+jSum("totalPaidChatsTimesInSeconds",cp,dur)+","+jSum("totalFreeCallsTimesInSeconds",kf,dur)+","+jSum("totalPaidCallsTimesInSeconds",kp,dur)+",\"uniqueConsultants\":{\"$addToSet\":\"$consultant_id\"},\"uniqueUsers\":{\"$addToSet\":\"$user_id\"}}}";
        return List.of(new Document("$match", df == null ? new Document() : df), Document.parse(g));
    }

    static List<Document> transactionStatsPipeline(Document df) {
        String cp="{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$eq\":[\"$session_info.mode\",\"chat\"]},{\"$ne\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\"FREE5MINUTES\"]}]}", kp="{\"$and\":[{\"$eq\":[\"$status\",\"completed\"]},{\"$in\":[\"$session_info.mode\",[\"audio\",\"video\"]]},{\"$ne\":[{\"$ifNull\":[\"$coupon.code\",\"\"]},\"FREE5MINUTES\"]}]}";
        String amt="{\"$ifNull\":[\"$onCompletion.consultantAmount\",0]}";
        return List.of(new Document("$match", df == null ? new Document() : df), Document.parse("{\"$group\":{\"_id\":null,\"totalTransactions\":{\"$sum\":1},"+jSum("totalChatRevenue",cp,amt)+","+jSum("totalCallRevenue",kp,amt)+",\"uniqueConsultants\":{\"$addToSet\":\"$consultant_id\"}}}"));
    }

    private void validateInsights(Map<String,String> q) {
        pos(q,"page",1,0); pos(q,"pageSize",10,100); validateDates(q); String sf=v(q,"sortField","consultantName");
        if (!SORT_FIELDS.contains(sf)) throw new IllegalArgumentException("\"sortField\" must be one of [consultantName, totalAttemptsForConsultation, totalPaidChatCallSpending, completionRate, paidConsultationConversionRate, consultantType]");
        String sd=v(q,"sortDirection","asc"); if (!Set.of("asc","desc").contains(sd)) throw new IllegalArgumentException("\"sortDirection\" must be one of [asc, desc]");
        validateTimeRange(q); String g=v(q,"consultantGender",null); if (g != null && !GENDERS.contains(g)) throw new IllegalArgumentException("\"consultantGender\" must be one of [male, female, transgender, ]");
    }
    private void validateSummary(Map<String,String> q) { validateDates(q); validateTimeRange(q); }
    private void pos(Map<String,String> q, String key, int def, int max) { String raw=v(q,key,String.valueOf(def)); try { int n=Integer.parseInt(raw); if (n<1) throw new IllegalArgumentException("\""+key+"\" must be greater than or equal to 1"); if (max>0 && n>max) throw new IllegalArgumentException("\""+key+"\" must be less than or equal to "+max); } catch (NumberFormatException e) { throw new IllegalArgumentException("\""+key+"\" must be a number"); } }
    private void validateTimeRange(Map<String,String> q) { String tr=v(q,"timeRange",null); if (tr!=null && !TIME_RANGES.contains(tr)) throw new IllegalArgumentException("\"timeRange\" must be one of [today, last7days, last30days, last60days, last90days, custom]"); }
    private void validateDates(Map<String,String> q) { String s=v(q,"startDate",null), e=v(q,"endDate",null); if (present(s)) jsDate(s); if (present(e)) jsDate(e); if (present(s)&&present(e)&&jsDate(e).before(jsDate(s))) throw new IllegalArgumentException("\"endDate\" must be greater than or equal to \"ref:startDate\""); }

    private int nodeCompare(Object l, Object r, String dir) { Object a=jsOr(l,0), b=jsOr(r,0); if (a instanceof String) { a=((String)a).toLowerCase(); b=String.valueOf(b).toLowerCase(); } int c=(a instanceof Number || b instanceof Number) ? Double.compare(num(a),num(b)) : String.valueOf(a).compareTo(String.valueOf(b)); return "asc".equals(dir) ? (c>0?1:-1) : (c<0?1:-1); }
    private boolean matches(Map<String,Object> i, String n, String raw) { if (String.valueOf(fb(i.get("consultantName"),"")).toLowerCase().contains(n)) return true; Object ct=i.get("consultantType"); if (ct instanceof Collection<?> c && c.stream().anyMatch(x -> String.valueOf(x).toLowerCase().contains(n))) return true; return String.valueOf(fb(i.get("consultantEmail"),"")).toLowerCase().contains(n) || String.valueOf(fb(i.get("consultantPhone"),"")).contains(raw) || String.valueOf(fb(i.get("totalAttemptChatCall"),0)).contains(raw); }

    private Document range(String tr) { ZonedDateTime now=ZonedDateTime.now(KOLKATA), start, end=now.toLocalDate().atTime(23,59,59,999_000_000).atZone(KOLKATA); switch (tr) { case "today" -> start=now.toLocalDate().atStartOfDay(KOLKATA); case "last7days" -> start=now.minusDays(7).toLocalDate().atStartOfDay(KOLKATA); case "last30days" -> start=now.minusDays(30).toLocalDate().atStartOfDay(KOLKATA); case "last60days" -> start=now.minusDays(60).toLocalDate().atStartOfDay(KOLKATA); case "last90days" -> start=now.minusDays(90).toLocalDate().atStartOfDay(KOLKATA); default -> throw new IllegalArgumentException("Unsupported timeRange: "+tr); } return new Document("$gte", Date.from(start.toInstant())).append("$lte", Date.from(end.toInstant())); }
    private Date jsDate(String raw) { try { return Date.from(Instant.parse(raw)); } catch(Exception ignored) {} try { return Date.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(raw, Instant::from)); } catch(Exception ignored) {} try { return Date.from(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant()); } catch(Exception ignored) {} try { return Date.from(LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)); } catch(Exception ignored) {} throw new IllegalArgumentException("\""+raw+"\" must be in ISO 8601 date format"); }

    private static String v(Map<String,String> q, String k, String d) { return q == null || !q.containsKey(k) || q.get(k)==null ? d : q.get(k); }
    private static boolean present(String s) { return s != null && !s.isEmpty(); }
    private static Object fb(Object v, Object f) { return v == null ? f : v; }
    private static Object truthy(Object v, Object f) { if (v == null) return f; if (v instanceof String s && s.isEmpty()) return f; if (v instanceof Number n && n.doubleValue()==0) return f; if (v instanceof Boolean b && !b) return f; return v; }
    private static Object jsOr(Object v, Object f) { return truthy(v, f); }
    private static double jsOr(double v, double f) { return v == 0 || Double.isNaN(v) ? f : v; }
    private static double num(Object v) { if (v instanceof Number n) return n.doubleValue(); if (v == null) return 0; try { return Double.parseDouble(String.valueOf(v)); } catch(Exception e) { return 0; } }
    private static void add(Map<String,Object> a, String ak, Map<String,Object> s, String sk) { a.put(ak, num(a.get(ak)) + num(s.get(sk))); }
    private static double round2(double v) { return Double.parseDouble(fixed2(v)); }
    private static String fixed2(double v) { return String.format(Locale.US, "%.2f", v); }
    private static Collection<?> coll(Object v) { return v instanceof Collection<?> c ? c : List.of(); }
}
