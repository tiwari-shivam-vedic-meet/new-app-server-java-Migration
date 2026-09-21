package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminConsultantService {
    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final AppConstants constants;

    public AdminConsultantService(MongoTemplate mongo, AdminMongoSupport support, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.constants = constants;
    }

    public Object listConsultants(Map<String, Object> body) {
        Map<String, Object> in = obj(body);
        Page p = page(in);
        Document params = consultantParams(in);
        List<Document> list = mongo.getCollection(Collections.CONSULTANTS).find(params)
                .sort(new Document("created_at", -1)).skip(p.skip).limit(p.limit)
                .projection(new Document("name", 1).append("userId", 1).append("countryCode", 1).append("details", 1)
                        .append("accountName", 1).append("primarySkills", 1).append("profileImage", 1).append("created_at", 1)
                        .append("status", 1).append("isFake", 1).append("isAdminVerify", 1).append("skills", 1)
                        .append("userName", 1).append("reviewFlagCount", 1).append("sessionsStatus", 1).append("wallet", 1).append("tags", 1))
                .into(new ArrayList<>());
        long total = mongo.getCollection(Collections.CONSULTANTS).countDocuments(params);
        long newCount = mongo.getCollection(Collections.CONSULTANTS).countDocuments(new Document("isAdminVerify", false));
        list.forEach(this::shapeConsultantListRow);
        sortMissed(list, in);
        // FAITHFUL(node-quirk): utils/classes/consultant.js:561-677 computes page-local review/waitlist stats; absent rows default to 0.
        return listTotal(list, list.isEmpty() ? 0 : total).append("newConsultantcount", newCount);
    }

    public Object onlineOfflineList(Map<String, Object> body) {
        Map<String, Object> in = obj(body);
        Page p = page(in);
        List<Document> rows = aggregate(Collections.CONSULTANTS, onlineOfflinePipeline(onlineOfflineParams(in), p.skip, p.limit, in));
        Document first = facetDoc(rows, "list", "count");
        long newCount = mongo.getCollection(Collections.CONSULTANTS).countDocuments(new Document("isAdminVerify", false));
        return listTotal(first.getList("list", Document.class, List.of()), facetTotal(first, "count", "total")).append("newConsultantcount", newCount);
    }

    public Object deleteListConsultant(Map<String, String> query) {
        Page p = page(query);
        Document params = new Document("isDeleted", true);
        String search = str(query, "search", "");
        if (!search.isBlank()) params.append("$or", List.of(rx("name", ".*" + search.trim() + ".*"), rx("email", ".*" + search.trim() + ".*"), rx("mobile", ".*" + search.trim() + ".*")));
        Document first = facetDoc(aggregate(Collections.CONSULTANTS, deleteListPipeline(params, p.skip, p.limit)), "list", "count");
        return listTotal(first.getList("list", Document.class, List.of()), facetTotal(first, "count", "total"));
    }

    public Object details(Map<String, String> query) {
        String idText = str(query, "consultantId", null);
        Object id = support.id(idText);
        if (mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", id)).first() == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST");
        Document out = aggregate(Collections.CONSULTANTS, detailsPipeline(id)).stream().findFirst().orElseGet(Document::new);
        Document wallet = mongo.getCollection(Collections.WALLETS).find(new Document("consultantId", idText)).first();
        // FAITHFUL(node-quirk): utils/classes/consultant.js:1526 decrypts wallet?.coins; external crypto seam returns stored value/null.
        out.put("wallet", wallet == null ? null : wallet.get("coins"));
        return out;
    }

    public Object numericalAnalytics(Map<String, String> query) {
        String idText = str(query, "consultantId", null);
        Object id = support.id(idText);
        if (mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", id)).first() == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST");
        return aggregate(Collections.CONSULTANTS, numericalAnalyticsPipeline(id)).stream().findFirst().orElseGet(this::numericalZero);
    }

    public Object download(Map<String, String> query) {
        // FAITHFUL(node-bug): rest-apis/modules/admin/consultant.js:229 calls missing ConsultantService.downloadExel.
        throw new IllegalStateException("ConsultantService.downloadExel is not a function");
    }

    public Object form16(Map<String, String> query) {
        Page p = page(query);
        Document params = new Document("consultantId", support.id(str(query, "consultantId", null)));
        String search = str(query, "search", "");
        if (!search.isBlank()) params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        List<Document> list = mongo.getCollection(Collections.CONSULTANT_FORMS_16).find(params).sort(new Document("createdAt", -1)).skip(p.skip).limit(p.limit).into(new ArrayList<>());
        list.forEach(this::withForm16);
        return listTotal(list, mongo.getCollection(Collections.CONSULTANT_FORMS_16).countDocuments(params));
    }

    public Object orderHistory(Map<String, String> query) {
        Page p = page(query);
        Document base = new Document("consultant_id", String.valueOf(str(query, "consultantId", null)));
        List<Document> ids = mongo.getCollection(Collections.WAITLISTS).find(base).sort(new Document("createdAt", -1)).skip(p.skip).limit(p.limit).projection(new Document("_id", 1)).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.WAITLISTS).countDocuments(base);
        if (ids.isEmpty()) return listTotal(List.of(), total);
        // FAITHFUL(node-quirk): utils/classes/consultant.js:2027 returns an envelope from service; Java service returns only inner result by repo contract.
        return listTotal(aggregate(Collections.WAITLISTS, orderHistoryPipeline(ids.stream().map(d -> d.get("_id")).toList())), total);
    }

    public Object waitlist(Map<String, String> query) {
        Document first = facetDoc(aggregate(Collections.WAITLISTS, waitlistPipeline(new Document("consultant_id", str(query, "consultantId", null)).append("status", "waiting"))), "order", "count");
        return listTotal(first.getList("order", Document.class, List.of()), facetTotal(first, "count", "total"));
    }

    public Object wallet(Map<String, Object> body) {
        Map<String, Object> in = obj(body);
        Page p = page(in);
        DateRange r = dateRange(str(in, "filter", "today"), in);
        Document params = new Document("isAdminVerify", true).append("isDeleted", false);
        String search = str(in, "search", "");
        if (!search.isBlank()) params.append("$or", List.of(rx("accountName", search), rx("name", search), rx("details.email", search), rx("details.phone", search)));
        String status = str(in, "status", "");
        if (!status.isBlank()) params.append("status", status.matches(".*true.*"));
        Document master = mongo.getCollection(Collections.MASTERS).find(new Document()).first();
        Number pg = payment(master, "PG", 2.5), tds = payment(master, "TDS", 10);
        List<Document> consultants = aggregate(Collections.CONSULTANTS, walletPipeline(params, r, p, str(in, "sortBy", "createdAt"), str(in, "sortOrder", "desc"), pg, tds));
        List<Document> totalAgg = aggregate(Collections.CONSULTANTS, walletTotalPipeline(params, r));
        long total = totalAgg.isEmpty() ? 0 : ((Number) totalAgg.get(0).getOrDefault("total", 0)).longValue();
        return new Document("consultants", consultants).append("total", total).append("page", p.page).append("limit", p.limit)
                .append("filter", in.getOrDefault("filter", "today")).append("filterBy", in.getOrDefault("filter", "today"))
                .append("dateRange", new Document("start", r.start).append("end", r.end)).append("rankingPeriod", new Document("start", r.start).append("end", r.end));
    }

    public Object avgRating(Map<String, Object> body) {
        String id = str(body, "consultantId", null);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Consultant ID is required");
        Document first = facetDoc(aggregate(Collections.REVIEW_AND_RATINGS, avgRatingPipeline(id)), "chatRating", "callRating");
        // FAITHFUL(node-quirk): utils/classes/consultant.js:3426 rank comes from ranking-service; rank calculation is left as local no-op seam.
        return new Document("chatAvgRating", avg(first, "chatRating", "avgRating")).append("callAvgRating", avg(first, "callRating", "avgRating"))
                .append("chatAvgRatingRank", null).append("callAvgRatingRank", null);
    }

    public Object avgTalkTime(Map<String, String> query) {
        String id = requireConsultantId(query);
        DateRange r = queryRange(query, 90);
        List<Document> mins = aggregate(Collections.WAITLISTS, avgTalkTimePipeline(id, r));
        List<Document> users = aggregate(Collections.WAITLISTS, uniqueUsersPipeline(id, r));
        int unique = users.isEmpty() ? 1 : Math.max(1, ((List<?>) users.get(0).getOrDefault("uniqueUsers", List.of())).size());
        return new Document("avgCallTime", (mins.isEmpty() ? 0 : dbl(mins.get(0).get("avgCallTime"))) / unique).append("avgCallTimeRank", null);
    }

    public Object totalChat(Map<String, String> query) {
        String id = requireConsultantId(query);
        return aggregate(Collections.CONSULTANTS, totalChatPipeline(support.id(id), queryRange(query, 90))).stream().findFirst().orElseGet(this::totalChatZero);
    }

    public Object availibilityRate(Map<String, String> query) {
        String id = requireConsultantId(query);
        // FAITHFUL(node-quirk): utils/classes/consultant.js:4077 tries app_chat_online service then falls back to stats app_online; only DB fallback is ported.
        List<Document> stats = mongo.getCollection(Collections.STATS).find(new Document("userType", "cons").append("userID", support.id(id)).append("for", "app_online").append("day", new Document("$in", lastDayKeys()))).into(new ArrayList<>());
        double seconds = stats.stream().mapToDouble(d -> dbl(d.getOrDefault("totalTime", 0))).sum();
        double avgSeconds = seconds / 7.0;
        String text = avgSeconds >= 3600 ? trim2(avgSeconds / 3600) + ((avgSeconds / 3600) == 1 ? " hour" : " hours") : Math.round(avgSeconds / 60) + (Math.round(avgSeconds / 60) == 1 ? " minute" : " minutes");
        return new Document("chatAvailibilityRate", 0).append("callAvailibilityRate", 0).append("onlineTimeTotalSeconds", round2(seconds)).append("onlineTimeFormatted", text)
                .append("chatAvailibilityRateRank", 0).append("callAvailibilityRateRank", 0);
    }

    public Object loyalCustomer(Map<String, String> query) {
        String id = requireConsultantId(query);
        Document f = facetDoc(aggregate(Collections.WAITLISTS, loyalCustomerPipeline(id, queryRange(query, 90))), "totalRepeatCus", "totalCus");
        int repeat = f.getList("totalRepeatCus", Document.class, List.of()).size();
        List<Document> total = f.getList("totalCus", Document.class, List.of());
        double all = total.isEmpty() ? 0 : dbl(total.get(0).get("totalUser"));
        return new Document("loyalCus", round2((repeat / all) * 100)).append("loyalCusRank", null);
    }

    public Object newCustomerConversion(Map<String, String> query) { return newCustomerConversionCore(requireConsultantId(query), queryRange(query, 90)); }
    public Object customerSatisfaction(Map<String, String> query) { return satisfaction(requireConsultantId(query), queryRange(query, 90)); }
    public Object newUserServeProperly(Map<String, String> query) { return serveProperly(requireConsultantId(query), false, queryRange(query, 90)); }
    public Object newCustomerRating(Map<String, String> query) { return aggregate(Collections.REVIEW_AND_RATINGS, avgRatingOfConsultantPipeline(requireConsultantId(query), queryRange(query, 7))); }
    public Object customerRetention(Map<String, String> query) { return retention(requireConsultantId(query), queryRange(query, 7)); }
    public Object newUserConversion(Map<String, String> query) { return newCustomerConversionCore(requireConsultantId(query), queryRange(query, 90)); }
    public Object userRetention(Map<String, String> query) { requireConsultantId(query); return newCustomerConversionCore(str(query, "consultantId", null), queryRange(Map.of(), 90)); }

    public Object shopifyDiscountCoupon(Map<String, String> query) {
        Page p = page(query);
        Document first = facetDoc(aggregate(Collections.SHOPIFY_DISCOUNT_COUPONS, shopifyPipeline(new Document("consultantId", support.id(str(query, "consultantId", null))), shopifySearch(str(query, "search", "")), p)), "list", "count");
        return listTotal(first.getList("list", Document.class, List.of()), facetTotal(first, "count", "total"));
    }

    public Object shopifyOrderCommission(Map<String, String> query) {
        // FAITHFUL(node-quirk): utils/classes/consultant.js:4940 calls Shopify API and writes wallet/orders; read migration keeps external transport as no-op SEAM.
        return null;
    }

    public Object shopifyOrder(Map<String, String> query) {
        Page p = page(query);
        Document first = facetDoc(aggregate(Collections.SHOPIFY_ORDERS, shopifyPipeline(new Document(), shopifySearch(str(query, "search", "")), p)), "list", "count");
        return listTotal(first.getList("list", Document.class, List.of()), facetTotal(first, "count", "total"));
    }

    public Object transactions(Map<String, String> query) {
        String id = str(query, "consultantId", null);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Consultant ID is required");
        Page p = page(query);
        Document match = new Document("consultantId", support.id(id)).append("userType", "cons");
        String search = str(query, "search", "");
        if (!search.isBlank()) match.append("$or", List.of(rx("transactionFor", ".*" + search.trim() + ".*"), rx("walletDeductReason", ".*" + search.trim() + ".*")));
        Document first = facetDoc(aggregate(Collections.WALLET_TRANSACTIONS, transactionsPipeline(match, p)), "data", "totalCount");
        long total = facetTotal(first, "totalCount", "count");
        return new Document("list", first.getList("data", Document.class, List.of())).append("total", total).append("page", p.page).append("limit", p.limit).append("totalPages", (long) Math.ceil(total / (double) p.limit));
    }

    public Object tags() {
        List<Document> list = mongo.getCollection(Collections.CONSULTANT_TAGS).find(new Document()).sort(new Document("createdAt", -1)).into(new ArrayList<>());
        list.forEach(this::withId);
        return list;
    }

    public Object addConsultant(Map<String, Object> body) { return objectTodo("/add"); }
    public Object updateConsultant(Map<String, Object> body) { return objectTodo("/update"); }
    public Object blockUnblock(Map<String, Object> body) { return objectTodo("/block_unblock"); }
    public Object toggleFakeness(Map<String, Object> body) { return objectTodo("/toggle_fakeness"); }
    public Object sendWarning(Map<String, Object> body) { return objectTodo("/send_warning"); }
    public Object uploadForm16(Map<String, Object> body) { return objectTodo("/upload_form16"); }
    public Object updateForm16(Map<String, Object> body) { return objectTodo("/update_form16"); }
    public Object blockUnblockForm16(Map<String, Object> body) { return objectTodo("/block_unblock_form16"); }
    public Object removeFromWaitlist(Map<String, Object> body) { return objectTodo("/remove_from_waitlist"); }
    public Object walletDeduction(Map<String, Object> body) { return objectTodo("/wallet/deduction"); }
    public Object walletDetails(String consultantId, Map<String, Object> body) { return objectTodo("/{consultantId}/wallet-details"); }
    public Object walletAddition(Map<String, Object> body) { return objectTodo("/wallet/addition"); }
    public Object markGreenTick(Map<String, Object> body) { return objectTodo("/mark_green_tick"); }
    public Object goLive(Map<String, Object> body) { return objectTodo("/go_live"); }
    public Object highPriority(Map<String, Object> body) { return objectTodo("/high_priority"); }
    public Object addShopifyDiscountCoupon(Map<String, Object> body) { return objectTodo("/shopify/discount_coupon/add"); }
    public Object updateShopifyDiscountCoupon(Map<String, Object> body) { return objectTodo("/shopify/discount_coupon/update"); }
    public Object deleteShopifyDiscountCoupon(Map<String, Object> body) { return objectTodo("/shopify/discount_coupon/delete"); }
    public Object updateShopifyDiscountCouponStatus(Map<String, Object> body) { return objectTodo("/shopify/discount_coupon/status"); }
    public Object logOut(Map<String, String> query) { return objectTodo("/log_out"); }
    public Object refundConsultation(Map<String, Object> body) { return objectTodo("/refund/consultation"); }
    public Object createTag(Map<String, Object> body) { return objectTodo("/tags"); }
    public Object setTags(Map<String, Object> body) { return objectTodo("/set_tags"); }
    public Object updateTag(Map<String, Object> body) { return objectTodo("/tags/update"); }
    public Object deleteTag(String tagId) { return objectTodo("/tags/{tagId}"); }

    List<Document> onlineOfflinePipeline(Document params, int skip, int limit, Map<String, Object> in) { return List.of(new Document("$match", params), typeStage(), lookup(Collections.REVIEW_AND_RATINGS, "reviewReplies"), new Document("$addFields", new Document("_idStr", new Document("$toString", "$_id"))), new Document("$lookup", new Document("from", Collections.WAITLISTS).append("localField", "_idStr").append("foreignField", "consultant_id").append("as", "consultations")), projectConsultant(), new Document("$sort", sortMissedDoc(in)), facet("list", "count", skip, limit)); }
    List<Document> deleteListPipeline(Document params, int skip, int limit) { return List.of(new Document("$match", params), new Document("$project", new Document("name",1).append("countryCode",1).append("mobile",1).append("email",1).append("gender",1).append("profileImage", image("$image")).append("createdAt",1).append("status",1).append("isAdminVerify",1).append("skills",1).append("userName",1).append("isDeleted",1).append("referId",1)), facet("list","count",skip,limit)); }
    List<Document> detailsPipeline(Object id) { return List.of(new Document("$match", new Document("_id", id).append("isDeleted", false)), new Document("$lookup", new Document("from", Collections.REVIEW_AND_RATINGS).append("as","ratingAverage").append("let", new Document("consultantId","$_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$$consultantId","$consultantId"))), new Document("$group", new Document("_id",null).append("average", new Document("$avg","$rating"))), new Document("$project", new Document("_id",0).append("average", new Document("$round", List.of("$average",1))))))), new Document("$lookup", new Document("from", Collections.WARNINGS).append("as","warningsDetails").append("let", new Document("consultantId","$_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$$consultantId","$consultantId")))))), new Document("$project", new Document("ratingAverage",1).append("warningsCounts", new Document("$size","$warningsDetails")).append("name",1).append("email","$details.email").append("countryCode","$details.phonePrefix").append("mobile","$details.phone").append("gender","$details.gender").append("profileImage", imagePreserve("$profileImage")).append("accountName",1).append("status",1).append("certificate", new Document("$map", new Document("input","$education.certificate").append("as","cert").append("in", new Document("$concat", List.of(constants.mediaUrl,"$$cert"))))))); }
    List<Document> numericalAnalyticsPipeline(Object id) { return List.of(new Document("$match", new Document("_id", id)), new Document("$addFields", new Document("_idStr", new Document("$toString","$_id"))), new Document("$lookup", new Document("from", Collections.WAITLISTS).append("localField","_idStr").append("foreignField","consultant_id").append("as","consultations")), new Document("$project", numericalProject(queryRange(Map.of(), 0)))); }
    List<Document> orderHistoryPipeline(List<Object> ids) { return List.of(new Document("$match", new Document("_id", new Document("$in", ids))), new Document("$lookup", new Document("from", Collections.CONSULTANTS).append("let", new Document("consultantId","$consultant_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq(new Document("$toObjectId","$$consultantId"),"$_id"))))).append("as","consultantDetail")), new Document("$lookup", new Document("from", Collections.USERS).append("let", new Document("userId","$user_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq(new Document("$toObjectId","$$userId"),"$_id"))))).append("as","userDetail")), new Document("$lookup", new Document("from", Collections.CHATS).append("let", new Document("consultantFormRequestId","$_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$consultantFormRequestId","$$consultantFormRequestId"))))).append("as","chats"))); }
    List<Document> waitlistPipeline(Document params) { return List.of(new Document("$match", params), new Document("$lookup", new Document("from", Collections.CONSULTANTS).append("let", new Document("consId","$consultant_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$_id", new Document("$toObjectId","$$consId")))))).append("as","consDetails")), new Document("$lookup", new Document("from", Collections.USERS).append("let", new Document("userId","$user_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$_id", new Document("$toObjectId","$$userId")))))).append("as","userDetails")), new Document("$unwind", new Document("path","$userDetails").append("preserveNullAndEmptyArrays", true)), new Document("$facet", new Document("order", List.of(new Document("$unwind","$consDetails"), new Document("$sort", new Document("createdAt",-1)))).append("count", List.of(new Document("$count","total"))))); }
    List<Document> avgRatingPipeline(String id) { return List.of(new Document("$match", new Document("consultantId", support.id(id)).append("status", true).append("createdAt", new Document("$gte", daysAgo(90))).append("$or", List.of(new Document("ratingType","chat"), new Document("ratingType","call")))), new Document("$facet", new Document("chatRating", List.of(new Document("$match", new Document("ratingType","chat")), new Document("$group", new Document("_id",null).append("avgRating", new Document("$avg","$rating"))))).append("callRating", List.of(new Document("$match", new Document("ratingType","call")), new Document("$group", new Document("_id",null).append("avgRating", new Document("$avg","$rating"))))))); }
    List<Document> avgTalkTimePipeline(String id, DateRange r) { return List.of(new Document("$match", sessionMatch(id,r,new Document("session_info.mode", new Document("$in", List.of("audio","video"))))), new Document("$group", new Document("_id",null).append("avgCallTime", new Document("$sum","$onCompletion.callDurationInSeconds")))); }
    List<Document> uniqueUsersPipeline(String id, DateRange r) { return List.of(new Document("$match", sessionMatch(id,r,new Document("session_info.mode", new Document("$in", List.of("audio","video"))))), new Document("$group", new Document("_id",null).append("uniqueUsers", new Document("$addToSet","$user_id")))); }
    List<Document> totalChatPipeline(Object id, DateRange r) { return List.of(new Document("$match", new Document("_id", id)), new Document("$addFields", new Document("_idStr", new Document("$toString","$_id"))), new Document("$lookup", new Document("from", Collections.WAITLISTS).append("localField","_idStr").append("foreignField","consultant_id").append("as","consultations")), new Document("$addFields", new Document("consultations", new Document("$filter", new Document("input","$consultations").append("as","consult").append("cond", and(gte("$$consult.createdAt",r.start), lte("$$consult.createdAt",r.end)))))), new Document("$project", numericalProject(r).append("totalPaidChats", sizeFilter(and(eq("$$consult.session_info.mode","chat"), new Document("$gt", List.of("$$consult.onCompletion.consultantAmount",0)), new Document("$ne", List.of("$$consult.coupon","FREE5MINUTES"))))).append("totalPaidCalls", sizeFilter(new Document("$in", List.of("$$consult.session_info.mode", List.of("audio","video"))))).append("repeatChats", sizeFilter(eq("$$consult.isNewUserForConsultant",false))).append("repeatCalls", sizeFilter(eq("$$consult.isNewUserForConsultant",false))).append("averageChatDuration",0).append("averageCallDuration",0))); }
    List<Document> loyalCustomerPipeline(String id, DateRange r) { return List.of(new Document("$match", new Document("consultant_id", id).append("createdAt", new Document("$gte",r.start).append("$lte",r.end)).append("status","completed")), new Document("$facet", new Document("totalRepeatCus", List.of(new Document("$group", new Document("_id","$user_id").append("totalRepeatUser", new Document("$sum",1))))).append("totalCus", List.of(new Document("$group", new Document("_id",null).append("totalUser", new Document("$sum",1))))))); }
    List<Document> avgRatingOfConsultantPipeline(String id, DateRange r) { return List.of(new Document("$match", new Document("consultantId", support.id(id)).append("status",true).append("createdAt", new Document("$gte",r.start).append("$lte",r.end))), new Document("$group", new Document("_id",null).append("averageRating", new Document("$avg","$rating")))); }
    List<Document> shopifyPipeline(Document params, Document search, Page p) { return List.of(new Document("$match", params), new Document("$lookup", new Document("from", Collections.CONSULTANTS).append("let", new Document("consultantId","$consultantId")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$_id","$$consultantId"))))).append("as","consultantDetail")), new Document("$unwind","$consultantDetail"), new Document("$match", search), facet("list","count",p.skip,p.limit)); }
    List<Document> transactionsPipeline(Document match, Page p) { return List.of(new Document("$match", match), new Document("$lookup", new Document("from", Collections.CONSULTANTS).append("let", new Document("consultantId","$consultantId")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$$consultantId","$_id"))))).append("as","consultantDetails")), new Document("$unwind","$consultantDetails"), new Document("$addFields", new Document("transactionTypeText", new Document("$cond", List.of(eq("$transactionType",0),"Credit","Debit"))).append("formattedAmount", new Document("$cond", List.of(eq("$transactionType",0), new Document("$concat", List.of("+₹", new Document("$toString","$coins"))), new Document("$concat", List.of("-₹", new Document("$toString","$coins"))))))), new Document("$sort", new Document("createdAt",-1)), new Document("$facet", new Document("data", List.of(new Document("$skip",p.skip), new Document("$limit",p.limit))).append("totalCount", List.of(new Document("$count","count"))))); }

    private Object newCustomerConversionCore(String id, DateRange r) { List<Document> free = mongo.getCollection(Collections.WAITLISTS).find(new Document("consultant_id", id).append("status","completed")).projection(new Document("user_id",1)).into(new ArrayList<>()); List<String> users = free.stream().map(d -> String.valueOf(d.get("user_id"))).toList(); List<Document> paid = aggregate(Collections.WAITLISTS, List.of(new Document("$match", new Document("consultant_id", id).append("user_id", new Document("$in", users)).append("status","completed").append("createdAt", new Document("$gte",r.start).append("$lte",r.end))), new Document("$group", new Document("_id","$user_id").append("total", new Document("$sum",1))))); return new Document("custConversion", (paid.size() / (double) free.size()) * 100).append("custConversionRank", null); }
    private Object satisfaction(String id, DateRange r) { List<Document> rows = aggregate(Collections.REVIEW_AND_RATINGS, List.of(new Document("$match", new Document("consultantId", support.id(id)).append("status", true).append("createdAt", new Document("$gte",r.start).append("$lte",r.end)).append("$or", List.of(new Document("ratingType","chat"), new Document("ratingType","call")))), new Document("$group", new Document("_id",null).append("avgRating", new Document("$avg","$rating"))))); return new Document("avgRating", rows.isEmpty()?0:round1(dbl(rows.get(0).get("avgRating")))).append("avgRatingRank", null); }
    private Object serveProperly(String id, boolean ignored, DateRange r) { List<Document> rows = aggregate(Collections.WAITLISTS, List.of(new Document("$match", new Document("consultant_id", id).append("status","completed")), new Document("$lookup", new Document("from", Collections.REVIEW_AND_RATINGS).append("let", new Document("consultId","$_id")).append("pipeline", List.of(new Document("$match", new Document("$expr", eq("$consultantRequestFormId","$$consultId"))))).append("as","reviewAndRating")), new Document("$unwind","$reviewAndRating"), new Document("$facet", new Document("totalServeCus", List.of(new Document("$match", new Document("$expr", and(new Document("$gte", List.of("$reviewAndRating.rating",1)), new Document("$ne", List.of("$reviewAndRating.review",""))))), new Document("$group", new Document("_id",null).append("totalServeProperlyUser", new Document("$sum",1))))).append("totalCus", List.of(new Document("$group", new Document("_id",null).append("totalUser", new Document("$sum",1)).append("newCusAvgRating", new Document("$avg","$reviewAndRating.rating")))))))); Document f = facetDoc(rows,"totalServeCus","totalCus"); List<Document> s=f.getList("totalServeCus",Document.class,List.of()), t=f.getList("totalCus",Document.class,List.of()); double sp=s.isEmpty()?0:dbl(s.get(0).get("totalServeProperlyUser")), all=t.isEmpty()?0:dbl(t.get(0).get("totalUser")); return new Document("serveProperlyPercentage",(sp/all)*100).append("serveProperlyPercentageRank",null); }
    private Object retention(String id, DateRange r) { List<Document> old = aggregate(Collections.WAITLISTS, List.of(new Document("$match", new Document("consultant_id",id).append("status","completed").append("createdAt", new Document("$gte",daysAgo(90)).append("$lte",r.end))), new Document("$group", new Document("_id","$user_id")))); List<String> users=old.stream().map(d->String.valueOf(d.get("_id"))).toList(); List<Document> recent=aggregate(Collections.WAITLISTS, List.of(new Document("$match", new Document("user_id", new Document("$in",users)).append("consultant_id",id).append("status","completed").append("createdAt",new Document("$gte",r.start).append("$lte",r.end))), new Document("$group",new Document("_id","$user_id")))); return new Document("customerRetention", old.isEmpty()?0:(recent.size()/(double)old.size())*100).append("customerRetentionRank",null); }

    private List<Document> walletPipeline(Document params, DateRange r, Page p, String sortBy, String sortOrder, Number pg, Number tds) { return List.of(new Document("$match", params), new Document("$lookup", new Document("from", Collections.WALLET_TRANSACTIONS).append("localField","_id").append("foreignField","consultantId").append("as","allTransactions")), new Document("$addFields", new Document("availableBalance","$wallet").append("totalCredits", txSum(0,r)).append("totalDebits", txSum(1,r)).append("filterPeriodEarning", txSum(0,r))), new Document("$addFields", new Document("netEarnings", new Document("$subtract", List.of("$totalCredits","$totalDebits"))).append("paymentGatewayCharge", new Document("$multiply", List.of("$totalCredits", new Document("$divide", List.of(pg,100))))).append("tdsCharge", new Document("$multiply", List.of("$totalCredits", new Document("$divide", List.of(tds,100))))).append("gstCharge",0).append("payableBalance", new Document("$subtract", List.of("$totalCredits","$totalDebits")))), new Document("$match", txExists()), new Document("$sort", new Document(sortBy, "desc".equals(sortOrder)?-1:1)), new Document("$skip",p.skip), new Document("$limit",p.limit)); }
    private List<Document> walletTotalPipeline(Document params, DateRange r) { return List.of(new Document("$match", params), new Document("$lookup", new Document("from", Collections.WALLET_TRANSACTIONS).append("localField","_id").append("foreignField","consultantId").append("as","allTransactions")), new Document("$addFields", new Document("totalCredits", txSum(0,r)).append("totalDebits", txSum(1,r))), new Document("$match", txExists()), new Document("$count","total")); }
    private Document numericalProject(DateRange r) { return new Document("walletBalance", new Document("$cond", new Document("if", and(new Document("$ne", java.util.Arrays.asList("$wallet",null)), new Document("$ne", List.of("$wallet","")))).append("then","$wallet").append("else",0))).append("totalEarnings", sizeFilter(eq("$$consult.status","completed"))).append("totalConsultationsChat", sizeFilter(eq("$$consult.session_info.mode","chat"))).append("totalConsultationsCall", sizeFilter(new Document("$in", List.of("$$consult.session_info.mode", List.of("audio","video"))))).append("totalRevenueGenerated", sizeFilter(new Document())).append("totalAmountRefunded", sizeFilter(eq("$$consult.refundDetail.isAmountRefunded",true))); }

    private List<Document> aggregate(String c, List<Document> p) { return mongo.getCollection(c).aggregate(p).into(new ArrayList<>()); }
    private Map<String,Object> obj(Map<String,Object> m){return m==null?new LinkedHashMap<>():new LinkedHashMap<>(m);}
    @SuppressWarnings("unchecked") private Document filter(Map<String,Object> m){Object f=m.get("filter"); return f instanceof Document d?d:f instanceof Map<?,?> map?new Document((Map<String,Object>)map):new Document();}
    private Document consultantParams(Map<String,Object> in){Document p=new Document("isAdminVerify",true).append("isDeleted",false); if(!str(in,"isNew","").isEmpty())p.put("isAdminVerify",false); if(!str(in,"status","").isEmpty())p.put("status",str(in,"status","").matches(".*true.*")); String s=str(in,"search",""); if(!s.isBlank())p.put("$or",List.of(rx("accountName",".*"+s.trim()+".*"),rx("name",".*"+s.trim()+".*"),rx("details.email",s.trim()),rx("details.phone",s.trim()))); Document f=filter(in); if(f.containsKey("skills"))p.put("skills",new Document("$elemMatch",new Document("$eq",f.get("skills")))); if(f.containsKey("gender"))p.put("details.gender",f.get("gender")); if(f.containsKey("rating"))p.put("rating",new Document("$elemMatch",new Document("$eq",f.get("rating")))); if(f.containsKey("expertise"))p.put("expertise",new Document("$elemMatch",new Document("$eq",f.get("expertise")))); if(f.containsKey("tag"))p.put("tags",support.id(f.get("tag"))); return p;}
    private Document onlineOfflineParams(Map<String,Object> in){Document p=consultantParams(in); Document f=filter(in); Document status = f.containsKey("status") && !Boolean.parseBoolean(String.valueOf(f.get("status"))) ? new Document("$and", List.of(new Document("sessionsStatus.isVoiceLive",false),new Document("sessionsStatus.isChatLive",false),new Document("sessionsStatus.isVideoLive",false))) : new Document("$or", List.of(new Document("sessionsStatus.isVoiceLive",true),new Document("sessionsStatus.isChatLive",true),new Document("sessionsStatus.isVideoLive",true))); p.putAll(status); return p;}
    private void shapeConsultantListRow(Document d){Document det=d.get("details",Document.class); d.put("mobile", det==null?null:det.get("phone")); d.put("email", det==null?null:det.get("email")); d.put("gender", det==null?null:det.get("gender")); d.put("consType", computedType(d.get("primarySkills"))); d.put("profileImage", absolute(d.getString("profileImage"))); d.put("walletBalance", d.getOrDefault("wallet",0)); d.putIfAbsent("reviewReplyCount",0); d.put("tags", stringifyList(d.get("tags")));}
    private String computedType(Object v){List<String> l=v instanceof List<?> x?x.stream().map(String::valueOf).toList():v instanceof String s&&!s.isBlank()?List.of(s.split(",")):List.of(); return l.contains("Graphology")?"Graphologist":l.contains("Tarot Card Reading")?"Tarot Reader":"Astrologer";}
    private void sortMissed(List<Document> l,Map<String,Object> in){Document s=sortMissedDoc(in);String k=s.keySet().iterator().next();int dir=((Number)s.get(k)).intValue();l.sort((a,b)->Double.compare(dbl(a.getOrDefault(k,0)),dbl(b.getOrDefault(k,0)))*dir);}
    private Document sortMissedDoc(Map<String,Object> in){Object m=filter(in).get("missed");return "miss".equals(m)?new Document("reviewFlagCount",-1):"flag".equals(m)?new Document("reviewReplyCount",-1):new Document("createdAt",-1);}
    private Document rx(String f,String s){return new Document(f,new Document("$regex",s).append("$options","i"));}
    private Document facet(String l,String c,int s,int lim){return new Document("$facet",new Document(l,List.of(new Document("$sort",new Document("createdAt",-1)),new Document("$skip",s),new Document("$limit",lim))).append(c,List.of(new Document("$count","total"))));}
    private Document facetDoc(List<Document> rows,String a,String b){return rows.isEmpty()?new Document(a,List.of()).append(b,List.of()):rows.get(0);}
    private long facetTotal(Document d,String arr,String fld){List<Document> c=d.getList(arr,Document.class,List.of());return c.isEmpty()?0:((Number)c.get(0).getOrDefault(fld,0)).longValue();}
    private Document listTotal(List<Document> l,long t){return new Document("list",l).append("total",t);}
    private String str(Map<?,?> m,String k,String f){Object v=m==null?null:m.get(k);return v==null?f:String.valueOf(v);}
    private Page page(Map<?,?> m){int p=jsInt(m==null?null:m.get("page"),1),l=jsInt(m==null?null:m.get("limit"),10);return new Page(p,l,(p-1)*l);}
    private int jsInt(Object v,int f){try{return v==null?f:Integer.parseInt(String.valueOf(v));}catch(Exception e){return f;}}
    private Object objectTodo(String r){return new Document("_todo","PORT consultant.js #"+r);}
    private String requireConsultantId(Map<String,String> q){String id=str(q,"consultantId",null);if(id==null||id.isBlank())throw new IllegalArgumentException("Consultant ID is required");return id;}
    private Document eq(Object a,Object b){return new Document("$eq",List.of(a,b));} private Document gte(Object a,Object b){return new Document("$gte",List.of(a,b));} private Document lte(Object a,Object b){return new Document("$lte",List.of(a,b));} private Document and(Object... p){return new Document("$and",List.of(p));}
    private Document image(String f){return new Document("$cond",List.of(new Document("$eq",List.of(f,"")),"",new Document("$concat",List.of(constants.mediaUrl,f))));}
    private Document imagePreserve(String f){return new Document("$cond",new Document("if",and(new Document("$ne",List.of(f,"")),new Document("$not",List.of(new Document("$regexMatch",new Document("input",f).append("regex","^https?://")))))).append("then",new Document("$concat",List.of(constants.mediaUrl,f))).append("else",f));}
    private Document typeStage(){return new Document("$addFields",new Document("consultantType",new Document("$switch",new Document("branches",List.of(new Document("case",new Document("$in",List.of("Graphology","$primarySkills"))).append("then","Graphologist"),new Document("case",new Document("$in",List.of("Tarot Card Reading","$primarySkills"))).append("then","Tarot Reader"))).append("default","Astrologer"))));}
    private Document lookup(String from,String as){return new Document("$lookup",new Document("from",from).append("let",new Document("consultantId","$_id")).append("pipeline",List.of(new Document("$match",new Document("$expr",eq("$consultantId","$$consultantId"))))).append("as",as));}
    private Document projectConsultant(){return new Document("$project",new Document("name",1).append("userId",1).append("countryCode",1).append("mobile","$details.phone").append("email","$details.email").append("gender","$details.gender").append("accountName",1).append("consType","$consultantType").append("profileImage",imagePreserve("$profileImage")).append("created_at",1).append("status",1).append("isFake",1).append("isAdminVerify",1).append("skills",1).append("userName",1).append("reviewFlagCount",1).append("reviewReplyCount",new Document("$size",new Document("$ifNull",List.of("$reviewReplies",List.of())))).append("sessionsStatus",1).append("walletBalance","$wallet"));}
    private Document sessionMatch(String id,DateRange r,Document extra){Document d=new Document("consultant_id",id).append("createdAt",new Document("$gte",r.start).append("$lte",r.end)).append("status","completed");d.putAll(extra);return d;}
    private Document sizeFilter(Object cond){return new Document("$size",new Document("$filter",new Document("input","$consultations").append("as","consult").append("cond",cond)));}
    private Document txSum(int type,DateRange r){return new Document("$sum",new Document("$map",new Document("input",new Document("$filter",new Document("input","$allTransactions").append("as","txn").append("cond",and(eq("$$txn.transactionType",type),eq("$$txn.isConsTransfer",false),gte("$$txn.createdAt",r.start),new Document("$lt",List.of("$$txn.createdAt",r.end)))))).append("as","txn").append("in",new Document("$toDouble","$$txn.coins"))));}
    private Document txExists(){return new Document("$or",List.of(new Document("totalCredits",new Document("$gt",0)),new Document("totalDebits",new Document("$gt",0))));}
    private Document shopifySearch(String s){return s==null||s.isBlank()?new Document():new Document("coupon",new Document("$regex",".*"+s+".*").append("$options","i")).append("consultantDetails.name",new Document("$regex",".*"+s+".*").append("$options","i"));}
    private Number payment(Document m,String k,double f){Document p=m==null?null:m.get("payment",Document.class);Object v=p==null?null:p.get(k);return v instanceof Number n?n:f;}
    private String absolute(String s){return s==null||s.isBlank()||s.matches("(?i)^https?://.*")?s:constants.mediaUrl+s;}
    private List<String> stringifyList(Object v){return v instanceof List<?> l?l.stream().map(String::valueOf).filter(s->!s.isBlank()).toList():List.of();}
    private void withId(Document d){if(d.get("_id")!=null)d.put("id",String.valueOf(d.get("_id")));}
    private void withForm16(Document d){withId(d);d.put("form16Pdf",d.getString("file")==null||d.getString("file").isBlank()?"":constants.mediaUrl+d.getString("file"));}
    private double avg(Document f,String a,String fld){List<Document> l=f.getList(a,Document.class,List.of());return l.isEmpty()?0:round1(dbl(l.get(0).get(fld)));}
    private double dbl(Object v){try{return v instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(v));}catch(Exception e){return 0;}}
    private double round1(double v){return Math.round(v*10.0)/10.0;} private double round2(double v){return Math.round(v*100.0)/100.0;} private String trim2(double v){double r=round2(v);return r==Math.rint(r)?String.valueOf((long)r):String.valueOf(r);}
    private Document numericalZero(){return new Document("walletBalance",0).append("totalEarnings",0).append("todaysEarnings",0).append("lastMonthsEarnings",0).append("totalConsultationsChat",0).append("totalConsultationsCall",0).append("totalMissedChatsAndCalls",0).append("totalFailedChatsAndCalls",0).append("totalRevenueGenerated",0).append("totalAmountRefunded",0);}
    private Document totalChatZero(){return new Document("walletBalance",0).append("totalEarnings",0).append("totalConsultationsChat",0).append("totalConsultationsCall",0).append("totalPaidChats",0).append("totalPaidCalls",0).append("repeatChats",0).append("repeatCalls",0).append("averageChatDuration",0).append("averageCallDuration",0).append("totalRevenueGenerated",0).append("totalAmountRefunded",0);}
    private DateRange queryRange(Map<String,String> q,int days){if(q!=null&&q.get("startDate")!=null&&q.get("endDate")!=null)return new DateRange(parseDate(q.get("startDate")),endOfDay(parseDate(q.get("endDate"))));return new DateRange(daysAgo(days),endOfDay(new Date()));}
    private DateRange dateRange(String filter,Map<String,Object> in){Calendar s=Calendar.getInstance(),e=Calendar.getInstance(); if("yesterday".equals(filter)){s.add(Calendar.DATE,-1);e.add(Calendar.DATE,-1);} else if("weekly".equals(filter)){s.add(Calendar.DATE,-7);} else if("custom".equals(filter)){return new DateRange(parseDate(in.get("startDate")),parseDate(in.get("endDate")));} s.set(Calendar.HOUR_OF_DAY,0);s.set(Calendar.MINUTE,0);s.set(Calendar.SECOND,0);s.set(Calendar.MILLISECOND,0);e.set(Calendar.HOUR_OF_DAY,0);e.set(Calendar.MINUTE,0);e.set(Calendar.SECOND,0);e.set(Calendar.MILLISECOND,0); if("today".equals(filter)||filter==null)e.add(Calendar.DATE,1); return new DateRange(s.getTime(),e.getTime());}
    private Date parseDate(Object o){try{return Date.from(Instant.parse(String.valueOf(o)));}catch(Exception e){return Date.from(LocalDate.parse(String.valueOf(o)).atStartOfDay(ZoneId.systemDefault()).toInstant());}}
    private Date daysAgo(int d){Calendar c=Calendar.getInstance();c.add(Calendar.DATE,-d);return c.getTime();} private Date endOfDay(Date d){Calendar c=Calendar.getInstance();c.setTime(d);c.set(Calendar.HOUR_OF_DAY,23);c.set(Calendar.MINUTE,59);c.set(Calendar.SECOND,59);c.set(Calendar.MILLISECOND,999);return c.getTime();}
    private List<String> lastDayKeys(){List<String> out=new ArrayList<>();for(int i=0;i<7;i++){Calendar d=Calendar.getInstance();d.add(Calendar.DATE,-i);out.add(String.format("%02d-%02d-%04d",d.get(Calendar.DAY_OF_MONTH),d.get(Calendar.MONTH)+1,d.get(Calendar.YEAR)));}return out;}
    record Page(int page,int limit,int skip){} record DateRange(Date start,Date end){}
}
