package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminInfluencersService {

    // VERIFIED: Node models influencer-settlement.model.js -> mongoose.model('influencer_settlements'),
    // influencer-ledger.model.js -> mongoose.model('influencer_ledger') pluralized to collection 'influencer_ledgers'.
    private static final String INFLUENCER_SETTLEMENTS = Collections.INFLUENCER_SETTLEMENTS;
    private static final String INFLUENCER_LEDGER = Collections.INFLUENCER_LEDGERS;

    private final MongoTemplate mongo;
    @SuppressWarnings("unused")
    private final AdminMongoSupport support;

    public AdminInfluencersService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Document create(Map<String, Object> body, String adminId) {
        Object name = raw(body, "name"), email = raw(body, "email"), phone = raw(body, "phone");
        Object sharePercent = raw(body, "sharePercent");
        // FAITHFUL(node-quirk): influencers.js:27 treats any falsy sharePercent (including numeric 0) as missing.
        if (!truthy(name) || !truthy(email) || !truthy(phone) || !truthy(sharePercent)) {
            throw new IllegalArgumentException("name, email, phone, and sharePercent are required.");
        }

        String normalizedEmail = text(email).toLowerCase().trim();
        Document existing = mongo.getCollection(Collections.INFLUENCERS)
                .find(new Document("email", normalizedEmail)).first();
        if (existing != null) throw new IllegalStateException("An influencer with this email already exists.");

        Date now = new Date();
        Document bankDetails = bankDetails(raw(body, "bankDetails"));
        Document influencer = new Document("_id", new ObjectId())
                .append("name", text(name).trim())
                .append("email", normalizedEmail)
                .append("phone", text(phone).trim())
                .append("sharePercent", parseFloat(sharePercent))
                .append("bankDetails", bankDetails)
                // FAITHFUL(node-quirk): influencers.js:41-43 collapses all falsy optional fields to defaults.
                .append("portalPassword", truthy(raw(body, "portalPassword")) ? raw(body, "portalPassword") : null)
                .append("notes", truthy(raw(body, "notes")) ? raw(body, "notes") : "")
                .append("createdBy", notBlank(adminId) ? strictObjectId(adminId) : null)
                .append("isActive", true)
                .append("createdAt", now)
                .append("updatedAt", now);
        mongo.getCollection(Collections.INFLUENCERS).insertOne(influencer);
        return influencer;
    }

    public Map<String, Object> list(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        int page = integer(q.get("page"), 0);
        int pageSize = integer(q.get("pageSize"), 20);
        Document filter = new Document();
        // FAITHFUL(node-quirk): influencers.js:59 treats any provided isActive value except literal "true" as false.
        if (q.containsKey("isActive")) filter.append("isActive", "true".equals(q.get("isActive")));
        if (truthy(q.get("search"))) {
            // FAITHFUL(node-quirk): influencer.service.js:276-280 uses caller search text as a raw Mongo regex pattern.
            filter.append("$or", List.of(
                    new Document("name", new Document("$regex", q.get("search")).append("$options", "i")),
                    new Document("email", new Document("$regex", q.get("search")).append("$options", "i")),
                    new Document("phone", new Document("$regex", q.get("search")).append("$options", "i"))));
        }

        // FAITHFUL(node-quirk): influencer.service.js:270-289 uses zero-based page * pageSize pagination.
        List<Document> influencers = mongo.getCollection(Collections.INFLUENCERS).find(filter)
                .sort(new Document("createdAt", -1)).skip(page * pageSize).limit(pageSize).into(new ArrayList<>());
        influencers.replaceAll(this::withoutPortalPassword);
        long total = mongo.getCollection(Collections.INFLUENCERS).countDocuments(filter);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("influencers", influencers);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    public Document single(String id) {
        Document influencer = mongo.getCollection(Collections.INFLUENCERS)
                .find(new Document("_id", strictObjectId(id))).first();
        if (influencer == null) throw new IllegalStateException("Influencer not found.");
        influencer = withoutPortalPassword(influencer);
        List<Document> coupons = mongo.getCollection(Collections.V2_COUPONS)
                .find(new Document("influencerId", influencer.get("_id")).append("isActive", true))
                .into(new ArrayList<>());
        Document result = new Document(influencer);
        result.put("coupons", coupons);
        return result;
    }

    public Document update(String id, Map<String, Object> body) {
        List<String> allowed = List.of("name", "phone", "sharePercent", "bankDetails", "notes", "isActive", "portalPassword");
        Document updates = new Document();
        if (body != null) {
            for (String field : allowed) {
                if (body.containsKey(field)) updates.append(field, body.get(field));
            }
        }
        // FAITHFUL(node-quirk): influencers.js:85-90 allows an empty $set update instead of rejecting it.
        updates.append("updatedAt", new Date());
        Document influencer = mongo.getCollection(Collections.INFLUENCERS).findOneAndUpdate(
                new Document("_id", strictObjectId(id)), new Document("$set", updates),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (influencer == null) throw new IllegalStateException("Influencer not found.");
        return withoutPortalPassword(influencer);
    }

    public Void deactivate(String id) {
        ObjectId influencerId = strictObjectId(id);
        // FAITHFUL(node-quirk): influencers.js:103-106 never checks whether the influencer exists before returning success.
        mongo.getCollection(Collections.INFLUENCERS).findOneAndUpdate(
                new Document("_id", influencerId), new Document("$set", new Document("isActive", false).append("updatedAt", new Date())));
        mongo.getCollection(Collections.V2_COUPONS).updateMany(
                new Document("influencerId", influencerId), new Document("$set", new Document("isActive", false).append("updatedAt", new Date())));
        return null;
    }

    public Document metrics(String id, Map<String, String> query) {
        List<Document> result = mongo.getCollection(INFLUENCER_LEDGER)
                .aggregate(metricsPipeline(strictObjectId(id), query == null ? Map.of() : query))
                .into(new ArrayList<>());
        if (!result.isEmpty()) return result.get(0);
        return new Document("totalEarnings", 0)
                .append("totalRefunds", 0)
                .append("totalUsers", 0)
                .append("totalRecharges", 0)
                .append("totalRechargeAmount", 0)
                .append("netEarnings", 0);
    }

    public Map<String, Object> ledger(String id, Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        int page = integer(q.get("page"), 0);
        int pageSize = integer(q.get("pageSize"), 20);
        List<Document> rows = mongo.getCollection(INFLUENCER_LEDGER)
                .aggregate(ledgerPipeline(strictObjectId(id), q, page, pageSize))
                .into(new ArrayList<>());
        Document result = rows.isEmpty() ? new Document() : rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", listValue(result.get("entries")));
        out.put("total", totalFacetCount(result.get("total")));
        out.put("page", page);
        out.put("pageSize", pageSize);
        return out;
    }

    public Map<String, Object> settlements(String id, Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        int page = integer(q.get("page"), 0);
        int pageSize = integer(q.get("pageSize"), 20);
        Document filter = new Document("influencerId", strictObjectId(id));
        // FAITHFUL(node-quirk): influencer.service.js:145-154 settlement history is also zero-based.
        List<Document> settlements = mongo.getCollection(INFLUENCER_SETTLEMENTS).find(filter)
                .sort(new Document("createdAt", -1)).skip(page * pageSize).limit(pageSize).into(new ArrayList<>());
        long total = mongo.getCollection(INFLUENCER_SETTLEMENTS).countDocuments(filter);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settlements", settlements);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    public Document settlementPreview(String id, Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        // FAITHFUL(node-quirk): influencers.js:153 validates both query values with simple falsy checks only.
        if (!truthy(q.get("periodStart")) || !truthy(q.get("periodEnd"))) {
            throw new IllegalArgumentException("periodStart and periodEnd are required.");
        }
        List<Document> result = mongo.getCollection(INFLUENCER_LEDGER)
                .aggregate(settlementPreviewPipeline(strictObjectId(id), q.get("periodStart"), q.get("periodEnd")))
                .into(new ArrayList<>());
        if (!result.isEmpty()) return result.get(0);
        return new Document("totalEarning", 0).append("totalRefunds", 0).append("netPayout", 0).append("count", 0);
    }

    public Document settle(String id, Map<String, Object> body, String adminId) {
        Object periodStart = raw(body, "periodStart"), periodEnd = raw(body, "periodEnd");
        // FAITHFUL(node-quirk): influencers.js:167 validates body dates before any ledger lookup.
        if (!truthy(periodStart) || !truthy(periodEnd)) throw new IllegalArgumentException("periodStart and periodEnd are required.");

        ObjectId infId = strictObjectId(id);
        Document filter = new Document("influencerId", infId)
                .append("status", "pending")
                .append("createdAt", new Document("$gte", date(text(periodStart))).append("$lte", date(text(periodEnd))));
        List<Document> ledgerEntries = mongo.getCollection(INFLUENCER_LEDGER).find(filter).into(new ArrayList<>());
        if (ledgerEntries.isEmpty()) throw new IllegalStateException("No pending ledger entries found in this period.");

        double totalEarning = 0, totalRefunds = 0;
        int rechargeCount = 0;
        for (Document entry : ledgerEntries) {
            if ("EARNING".equals(entry.getString("type"))) {
                totalEarning += number(entry.get("amount"));
                rechargeCount++;
            }
            if ("REFUND".equals(entry.getString("type"))) totalRefunds += Math.abs(number(entry.get("amount")));
        }
        // FAITHFUL(node-quirk): influencer.service.js:195 floors negative payouts to 0 before rejecting them.
        double netPayout = Math.max(0, Double.parseDouble(String.format(java.util.Locale.US, "%.2f", totalEarning - totalRefunds)));
        if (netPayout <= 0) throw new IllegalStateException("Net payout is zero or negative. Cannot create settlement.");

        Date now = new Date();
        Document settlement = new Document("_id", new ObjectId())
                .append("influencerId", infId)
                .append("periodStart", date(text(periodStart)))
                .append("periodEnd", date(text(periodEnd)))
                .append("totalEarning", totalEarning)
                .append("totalRefunds", totalRefunds)
                .append("netPayout", netPayout)
                .append("rechargeCount", rechargeCount)
                .append("status", "paid")
                .append("paidAt", now)
                .append("paidBy", notBlank(adminId) ? strictObjectId(adminId) : null)
                // FAITHFUL(node-quirk): influencer.service.js:214-215 collapses any falsy settlement text fields to "".
                .append("paymentReference", truthy(raw(body, "paymentReference")) ? raw(body, "paymentReference") : "")
                .append("remarks", truthy(raw(body, "remarks")) ? raw(body, "remarks") : "")
                .append("createdAt", now)
                .append("updatedAt", now);
        mongo.getCollection(INFLUENCER_SETTLEMENTS).insertOne(settlement);
        List<Object> ledgerIds = ledgerEntries.stream().map(entry -> entry.get("_id")).toList();
        mongo.getCollection(INFLUENCER_LEDGER).updateMany(
                new Document("_id", new Document("$in", ledgerIds)),
                new Document("$set", new Document("status", "paid").append("settlementId", settlement.get("_id"))
                        .append("updatedAt", new Date())));
        return settlement;
    }

    static List<Document> metricsPipeline(ObjectId influencerId, Map<String, String> query) {
        Document matchStage = new Document("influencerId", influencerId);
        if (truthy(query.get("from")) || truthy(query.get("to"))) {
            Document createdAt = new Document();
            if (truthy(query.get("from"))) createdAt.append("$gte", date(query.get("from")));
            if (truthy(query.get("to"))) createdAt.append("$lte", date(query.get("to")));
            matchStage.append("createdAt", createdAt);
        }
        Document match = Document.parse("{\"$match\":{}}");
        match.put("$match", matchStage);
        return Arrays.asList(
                match,
                Document.parse("{\"$group\":{\"_id\":null,\"totalEarnings\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$type\",\"EARNING\"]},\"$amount\",0]}},\"totalRefunds\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$type\",\"REFUND\"]},{\"$abs\":\"$amount\"},0]}},\"uniqueUsers\":{\"$addToSet\":\"$userId\"},\"rechargeCount\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$type\",\"EARNING\"]},1,0]}},\"totalRechargeAmount\":{\"$sum\":\"$rechargeBaseAmount\"}}}"),
                Document.parse("{\"$project\":{\"_id\":0,\"totalEarnings\":1,\"totalRefunds\":1,\"totalUsers\":{\"$size\":\"$uniqueUsers\"},\"totalRecharges\":\"$rechargeCount\",\"totalRechargeAmount\":1,\"netEarnings\":{\"$subtract\":[\"$totalEarnings\",\"$totalRefunds\"]}}}"));
    }

    static List<Document> ledgerPipeline(ObjectId influencerId, Map<String, String> query, int page, int pageSize) {
        Document matchStage = new Document("influencerId", influencerId);
        if (truthy(query.get("from")) || truthy(query.get("to"))) {
            Document createdAt = new Document();
            if (truthy(query.get("from"))) createdAt.append("$gte", date(query.get("from")));
            if (truthy(query.get("to"))) createdAt.append("$lte", date(query.get("to")));
            matchStage.append("createdAt", createdAt);
        }
        if (truthy(query.get("type"))) matchStage.append("type", query.get("type"));
        if (truthy(query.get("status"))) matchStage.append("status", query.get("status"));
        Document match = Document.parse("{\"$match\":{}}");
        match.put("$match", matchStage);
        Document facet = Document.parse("{\"$facet\":{\"entries\":[{\"$sort\":{\"createdAt\":-1}},{\"$skip\":0},{\"$limit\":20},{\"$project\":{\"type\":1,\"amount\":1,\"rechargeBaseAmount\":1,\"couponCode\":1,\"status\":1,\"refundReason\":1,\"createdAt\":1,\"user.name\":1,\"user.details.phone\":1}}],\"total\":[{\"$count\":\"count\"}]}}");
        @SuppressWarnings("unchecked")
        List<Document> entries = (List<Document>) document(facet.get("$facet")).get("entries");
        entries.get(1).put("$skip", page * pageSize);
        entries.get(2).put("$limit", pageSize);
        return Arrays.asList(
                match,
                Document.parse("{\"$lookup\":{\"from\":\"users\",\"localField\":\"userId\",\"foreignField\":\"_id\",\"pipeline\":[{\"$project\":{\"name\":1,\"details.phone\":1}}],\"as\":\"user\"}}"),
                Document.parse("{\"$addFields\":{\"user\":{\"$arrayElemAt\":[\"$user\",0]}}}"),
                facet);
    }

    static List<Document> settlementPreviewPipeline(ObjectId influencerId, String periodStart, String periodEnd) {
        Document match = Document.parse("{\"$match\":{\"status\":\"pending\",\"createdAt\":{}}}");
        Document matchBody = document(match.get("$match"));
        matchBody.put("influencerId", influencerId);
        matchBody.put("createdAt", new Document("$gte", date(periodStart)).append("$lte", date(periodEnd)));
        return Arrays.asList(
                match,
                Document.parse("{\"$group\":{\"_id\":null,\"totalEarning\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$type\",\"EARNING\"]},\"$amount\",0]}},\"totalRefunds\":{\"$sum\":{\"$cond\":[{\"$eq\":[\"$type\",\"REFUND\"]},{\"$abs\":\"$amount\"},0]}},\"count\":{\"$sum\":1}}}"),
                Document.parse("{\"$project\":{\"_id\":0,\"totalEarning\":1,\"totalRefunds\":1,\"count\":1,\"netPayout\":{\"$max\":[0,{\"$subtract\":[\"$totalEarning\",\"$totalRefunds\"]}]}}}"));
    }

    private static int totalFacetCount(Object raw) {
        List<Document> total = listValue(raw);
        if (total.isEmpty()) return 0;
        Object count = total.get(0).get("count");
        return count instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> listValue(Object raw) {
        return raw instanceof List<?> list ? (List<Document>) list : List.of();
    }

    private static Object raw(Map<?, ?> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static Document document(Object raw) {
        return raw instanceof Document d ? d : new Document((Map<String, Object>) raw);
    }

    private Document withoutPortalPassword(Document influencer) {
        Document copy = new Document(influencer);
        copy.remove("portalPassword");
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Document bankDetails(Object raw) {
        Document defaults = new Document("accountHolderName", "")
                .append("accountNumber", "")
                .append("ifscCode", "")
                .append("bankName", "")
                .append("upiId", "");
        if (!truthy(raw)) return defaults;
        if (raw instanceof Document document) {
            defaults.putAll(document);
        } else if (raw instanceof Map<?, ?> map) {
            defaults.putAll((Map<String, Object>) map);
        }
        return defaults;
    }

    private static ObjectId strictObjectId(Object raw) {
        return new ObjectId(text(raw));
    }

    private static Date date(String raw) {
        try { return Date.from(Instant.parse(raw)); }
        catch (Exception ignored) { return Date.from(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant()); }
    }

    private static int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static double number(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(text(raw)); }
        catch (Exception ignored) { return 0; }
    }

    private static double parseFloat(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        String value = text(raw).trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
                .matcher(value);
        return matcher.find() ? Double.parseDouble(matcher.group()) : Double.NaN;
    }

    private static boolean truthy(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        return !String.valueOf(raw).isEmpty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
