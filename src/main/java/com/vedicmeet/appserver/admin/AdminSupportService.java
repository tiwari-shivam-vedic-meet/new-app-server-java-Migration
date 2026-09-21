package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.crypto.CryptoUtil;
import com.vedicmeet.appserver.support.ChatServerClient;
import com.vedicmeet.appserver.support.SupportService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native port of the fourteen production admin/support routes. */
@Service
public class AdminSupportService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport db;
    private final SupportService support;
    private final ChatServerClient chat;
    private final CryptoUtil crypto;
    private final TransactionTemplate transactions;

    public AdminSupportService(MongoTemplate mongo, AdminMongoSupport db, SupportService support,
                               ChatServerClient chat, CryptoUtil crypto,
                               MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.db = db;
        this.support = support;
        this.chat = chat;
        this.crypto = crypto;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> masters(Map<String, String> query) {
        Document filter = new Document();
        if (notBlank(query.get("supportType"))) filter.append("supportType", query.get("supportType"));
        if ("TECHNICAL".equals(query.get("supportType")) && notBlank(query.get("userType")))
            filter.append("userType", query.get("userType"));
        db.addSearch(filter, query.get("search"), List.of("question", "description", "mobile"));
        return db.page(Collections.SUPPORT_MASTERS, filter, new Document("createdAt", -1),
                integer(query.get("page"), 1), integer(query.get("limit"), 10));
    }

    public Document addMaster(Map<String, Object> input) {
        String question = AdminResponses.text(input, "question");
        String supportType = AdminResponses.text(input, "supportType");
        Document duplicate = new Document("supportType", supportType).append("question", question);
        if ("TECHNICAL".equals(supportType)) duplicate.append("userType", input.get("userType"));
        if (mongo.getCollection(Collections.SUPPORT_MASTERS).find(duplicate).first() != null)
            throw new IllegalStateException("QUESTION_EXIST");
        Document document = new Document(input).append("createdAt", new Date()).append("updatedAt", new Date());
        mongo.getCollection(Collections.SUPPORT_MASTERS).insertOne(document);
        return document;
    }

    public Document updateMaster(Map<String, Object> input) {
        String id = AdminResponses.text(input, "supportId");
        db.requireById(Collections.SUPPORT_MASTERS, id, "SUPPORT_NOT_EXIST");
        if (input.get("question") != null) {
            Document duplicate = new Document("_id", new Document("$ne", db.id(id)))
                    .append("question", input.get("question"));
            if ("TECHNICAL".equals(input.get("supportType"))) duplicate.append("userType", input.get("userType"));
            if (mongo.getCollection(Collections.SUPPORT_MASTERS).find(duplicate).first() != null)
                throw new IllegalStateException("QUESTION_EXIST");
        }
        Document changes = new Document(input); changes.remove("supportId"); changes.remove("_id");
        return db.updateById(Collections.SUPPORT_MASTERS, id, changes, "SUPPORT_NOT_EXIST");
    }

    public Map<String, Object> queries(Map<String, String> query) {
        Document filter = new Document();
        if (notBlank(query.get("userType"))) filter.append("userType", query.get("userType"));
        if (notBlank(query.get("problemType"))) filter.append("supportType", query.get("problemType"));
        db.addSearch(filter, query.get("search"), List.of("question", "description", "ticketNumber", "title"));
        Map<String, Object> result = db.page(Collections.CUSTOMER_SUPPORT_QUERIES, filter,
                new Document("createdAt", -1), integer(query.get("page"), 1), integer(query.get("limit"), 10));
        enrichQueries(result);
        return result;
    }

    public Map<String, Object> insights() {
        Date since = new Date(System.currentTimeMillis() - 86400000L);
        var collection = mongo.getCollection(Collections.CUSTOMER_SUPPORT_QUERIES);
        return Map.of("totalQueries", collection.countDocuments(),
                "totalQueriesResolved", collection.countDocuments(new Document("status", "resolved")),
                "totalPending", collection.countDocuments(new Document("status", "pending")),
                "last24HoursTotalQueries", collection.countDocuments(new Document("createdAt", new Document("$gte", since))),
                "last24HoursTotalQueriesResolved", collection.countDocuments(new Document("status", "resolved").append("createdAt", new Document("$gte", since))),
                "last24HoursTotalPending", collection.countDocuments(new Document("status", "pending").append("createdAt", new Document("$gte", since))));
    }

    public Document updateAdminReply(String id) {
        return db.updateById(Collections.CUSTOMER_SUPPORT_QUERIES, id,
                new Document("isAdminReply", true), "QUERY_NOT_EXIST");
    }

    public Document sendChat(Map<String, Object> input, Document admin, MultipartFile file) {
        return support.supportChat(input, admin, "", file);
    }

    public Map<String, Object> chats(String queryId, int page, int limit) {
        return db.page(Collections.CUSTOMER_SUPPORT_CHATS,
                new Document("customerSupportQueryId", db.id(queryId)), new Document("createdAt", -1), page, limit);
    }

    public Document queryDetails(String queryId) {
        Document query = db.requireById(Collections.CUSTOMER_SUPPORT_QUERIES, queryId, "Query not found");
        enrichQuery(query);
        return query;
    }

    public Document updateQueryStatus(String queryId, String status, Object closedAt) {
        Document changes = new Document("isResolved", true);
        if (notBlank(status)) changes.append("status", status);
        if (closedAt != null) changes.append("closedAt", closedAt);
        return db.updateById(Collections.CUSTOMER_SUPPORT_QUERIES, queryId, changes, "QUERY_NOT_EXIST");
    }

    /** Atomic replacement for Node's two independent wallet promises. */
    public Document refund(Map<String, Object> input) {
        String requestId = firstText(input, "consultantformrequestId", "waitlistId");
        String supportId = optional(input, "customerSupportId");
        if ("admin".equals(input.get("requestFrom")) && supportId != null)
            db.requireById(Collections.CUSTOMER_SUPPORT_QUERIES, supportId, "INVALID_CUSTOMER_SUPPORT_ID");
        Document before = db.requireById(Collections.CONSULTANT_FORM_REQUESTS, requestId, "INVALID_ORDER_HISTORY_ID");
        if (Boolean.TRUE.equals(before.get("isAmountRefunded"))) throw new IllegalStateException("AMOUNT_ALREADY_REFUNDED");

        Document result = transactions.execute(tx -> {
            Date now = new Date();
            Document claimed = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).findOneAndUpdate(
                    new Document("_id", db.id(requestId)).append("isAmountRefunded", new Document("$ne", true)),
                    new Document("$set", new Document("isAmountRefunded", true).append("refundDetail",
                            new Document("refundAmount", number(before.get("totalAmountPayToConsultant"))
                                    + number(before.get("totalAmountPayToPlateform")))
                                    .append("refundDate", now).append("refundReason", input.get("refundReason"))
                                    .append("refundedBy", input.getOrDefault("requestFrom", "admin")))),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (claimed == null) throw new IllegalStateException("AMOUNT_ALREADY_REFUNDED");

            Object userId = db.id(claimed.get("userId"));
            Object consultantId = db.id(claimed.get("consultantId"));
            double consultantAmount = number(claimed.get("totalAmountPayToConsultant"));
            double refundAmount = consultantAmount + number(claimed.get("totalAmountPayToPlateform"));
            adjustWallet(userId, null, refundAmount, true);
            adjustWallet(null, consultantId, -consultantAmount, false);
            mongo.getCollection(Collections.USERS).updateOne(new Document("_id", userId), new Document("$inc", new Document("wallet", refundAmount)));
            mongo.getCollection(Collections.CONSULTANTS).updateOne(new Document("_id", consultantId), new Document("$inc", new Document("wallet", -consultantAmount)));
            mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertMany(List.of(
                    ledger(claimed, userId, consultantId, "user", refundAmount, 0, now),
                    ledger(claimed, userId, consultantId, "cons", consultantAmount, 0, now)));
            return claimed;
        });
        if (result == null) throw new IllegalStateException("REFUND_FAILED");
        return result;
    }

    public void deleteMaster(String supportId) {
        db.requireById(Collections.SUPPORT_MASTERS, supportId, "SUPPORT_NOT_EXIST");
        mongo.getCollection(Collections.SUPPORT_MASTERS).deleteOne(new Document("_id", db.id(supportId)));
    }

    public Document initiateQuery(Map<String, Object> input) {
        String userType = AdminResponses.text(input, "userType");
        Object targetId = db.id(AdminResponses.text(input, "userId"));
        Document filter = new Document("supportType", input.get("supportType")).append("isResolved", false)
                .append("title", input.get("title"));
        if ("admin".equals(userType)) {
            filter.append("userId", targetId); input.put("userId", targetId);
        } else {
            filter.append("consultantId", targetId); input.put("consultantId", targetId);
        }
        if (mongo.getCollection(Collections.CUSTOMER_SUPPORT_QUERIES).find(filter).first() != null)
            throw new IllegalStateException("Query is already exist in pending state");
        input.put("ticketNumber", nextTicket());
        Date now = new Date();
        Document query = new Document(input).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.CUSTOMER_SUPPORT_QUERIES).insertOne(query);
        return query;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chatHistory(String threadId, int page, int limit) {
        Map<String, Object> response = chat.threadMessages(threadId, page, limit);
        if (!Boolean.TRUE.equals(response.get("success"))) throw new IllegalStateException("Failed to get thread messages!");
        Object raw = response.get("data");
        Map<String, Object> data = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        long total = data.get("total") instanceof Number n ? n.longValue() : 0;
        return Map.of("messages", data.getOrDefault("messages", List.of()), "total", total,
                "page", page, "limit", limit, "totalPages", (long) Math.ceil(total / (double) Math.max(1, limit)));
    }

    private void adjustWallet(Object userId, Object consultantId, double delta, boolean user) {
        Document filter = user ? new Document("userId", userId) : new Document("consultantId", consultantId);
        Document wallet = mongo.getCollection(Collections.WALLETS).find(filter).first();
        if (wallet == null) throw new IllegalStateException("WALLET_NOT_EXIST");
        double current = decrypt(wallet.get("coins"));
        mongo.getCollection(Collections.WALLETS).updateOne(filter,
                new Document("$set", new Document("coins", crypto.encrypt(jsNumber(current + delta))).append("updatedAt", new Date())));
    }

    private Document ledger(Document request, Object userId, Object consultantId, String userType,
                            double amount, int transactionType, Date now) {
        return new Document("_id", new ObjectId()).append("userId", userId).append("consultantId", consultantId)
                .append("transactionFor", "refund").append("userType", userType)
                .append("totalAmountPayToPlateform", 0).append("coins", amount).append("amount", amount)
                .append("transactionType", transactionType).append("requestId", request.get("_id"))
                .append("createdAt", now).append("updatedAt", now);
    }

    @SuppressWarnings("unchecked")
    private void enrichQueries(Map<String, Object> result) {
        List<Document> list = (List<Document>) result.getOrDefault("list", List.of());
        list.forEach(this::enrichQuery);
    }

    private void enrichQuery(Document query) {
        Document user = db.findById(Collections.USERS, query.get("userId"));
        Document consultant = db.findById(Collections.CONSULTANTS, query.get("consultantId"));
        if (user != null) query.put("userDetails", actor(user));
        if (consultant != null) query.put("consultantDetails", actor(consultant));
    }

    private Document actor(Document value) {
        Document details = value.get("details") instanceof Document d ? d : new Document();
        return new Document("_id", value.get("_id")).append("name", first(value.get("userName"), value.get("name")))
                .append("email", first(details.get("email"), value.get("email"))).append("mobile", details.get("phone"))
                .append("userId", value.get("userId")).append("profileImage", first(value.get("profileImage"), value.get("image")));
    }

    private String nextTicket() {
        Document last = mongo.getCollection(Collections.CUSTOMER_SUPPORT_QUERIES).find()
                .sort(new Document("createdAt", -1)).projection(new Document("ticketNumber", 1)).first();
        int number = 0;
        if (last != null && last.get("ticketNumber") != null) {
            String[] parts = String.valueOf(last.get("ticketNumber")).split("_");
            try { number = Integer.parseInt(parts[parts.length - 1]); } catch (Exception ignored) {}
        }
        return "#QUERY_" + (number + 1);
    }

    private String firstText(Map<String, Object> input, String first, String second) {
        String value = optional(input, first); return notBlank(value) ? value : AdminResponses.text(input, second);
    }
    private String optional(Map<String, Object> input, String key) { Object v = input == null ? null : input.get(key); return v == null ? null : String.valueOf(v); }
    private int integer(String raw, int fallback) { try { return raw == null ? fallback : Integer.parseInt(raw); } catch (Exception ignored) { return fallback; } }
    private double number(Object raw) { try { return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw)); } catch (Exception ignored) { return 0; } }
    private double decrypt(Object raw) { try { return raw == null ? 0 : Double.parseDouble(crypto.decrypt(String.valueOf(raw))); } catch (Exception ignored) { return 0; } }
    private String jsNumber(double v) { return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private Object first(Object a, Object b) { return a == null || String.valueOf(a).isBlank() ? b : a; }
}
