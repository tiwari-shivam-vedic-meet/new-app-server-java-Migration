package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminFlagService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final PushNotificationService push;

    public AdminFlagService(MongoTemplate mongo, AdminMongoSupport support, PushNotificationService push) {
        this.mongo = mongo;
        this.support = support;
        this.push = push;
    }

    public Map<String, Object> list(Map<String, String> query) {
        Map<String, String> q = query == null ? Map.of() : query;
        int page = integer(q.get("page"), 0); // FAITHFUL(node-quirk): admin flag list is zero-based — rest-apis/modules/admin/flag.js:12-13
        int pageSize = integer(q.get("pageSize"), 1000);
        Document filterQuery = filterQuery(q);
        Document sortQuery = sortQuery(q);
        List<Document> flags = mongo.getCollection(Collections.FLAG_LOGS)
                .aggregate(listPipeline(filterQuery, sortQuery, page * pageSize, pageSize))
                .into(new ArrayList<>());
        long totalCount = mongo.getCollection(Collections.FLAG_LOGS).countDocuments(filterQuery);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("pageCount", (int) Math.ceil(totalCount / (double) pageSize));
        pagination.put("pageNumber", page);
        pagination.put("totalDocuments", totalCount);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", flags);
        data.put("pagination", pagination);
        return data;
    }

    public String update(Map<String, Object> body) {
        String flagId = required(body, "flagId", "FLAG_ID_REQUIRE");
        Object status = body == null ? null : body.get("status");
        Document flag = support.findById(Collections.FLAG_LOGS, strictObjectId(flagId));
        if (flag == null || "resolved".equals(flag.getString("flag_status"))) {
            throw new IllegalStateException("Flag already resolved");
        }

        // FAITHFUL(node-quirk): any status/type other than resolved + negative_feedback is a successful no-op — rest-apis/modules/admin/flag.js:164-181
        if ("resolved".equals(value(status)) && "negative_feedback".equals(flag.getString("flag_type"))) {
            mongo.getCollection(Collections.FLAG_LOGS).updateOne(
                    new Document("_id", strictObjectId(flagId)),
                    new Document("$set", new Document("flag_status", status)));
            mongo.getCollection(Collections.CONSULTANTS).updateOne(
                    new Document("_id", support.id(flag.get("consultant_id"))),
                    new Document("$inc", new Document("limit.flags.current", 1)));
            Document temporaryData = document(flag.get("temporary_data"));
            mongo.getCollection(Collections.REVIEW_AND_RATINGS).findOneAndUpdate(
                    new Document("_id", support.id(temporaryData == null ? null : temporaryData.get("feedback_id"))),
                    new Document("$set", new Document("isFlag", true).append("status", false).append("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        }
        return "Successfully updated flag status!";
    }

    public String refundRequest(Map<String, Object> body) {
        String flagId = required(body, "flagId", "FLAG_ID_REQUIRE");
        Object status = body == null ? null : body.get("status");
        Document flag = support.findById(Collections.FLAG_LOGS, strictObjectId(flagId));
        if (flag == null || "resolved".equals(flag.getString("flag_status"))) {
            throw new IllegalStateException("Flag already resolved");
        }

        // FAITHFUL(node-quirk): any status/type other than resolved + refund_request is a successful no-op — rest-apis/modules/admin/flag.js:204-267
        if ("resolved".equals(value(status)) && "refund_request".equals(flag.getString("flag_type"))) {
            // FAITHFUL(node-quirk): flag_status is updated before refund validations, so later failures still leave it resolved — rest-apis/modules/admin/flag.js:204-209
            mongo.getCollection(Collections.FLAG_LOGS).updateOne(
                    new Document("_id", strictObjectId(flagId)),
                    new Document("$set", new Document("flag_status", status)));

            Object waitlistId = flag.get("waitlist_id");
            Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                    .find(new Document("_id", strictObjectId(waitlistId))).first();
            if (waitlist == null) throw new IllegalStateException("Waitlist not found");

            Document coupon = document(waitlist.get("coupon"));
            if (coupon != null && "first_purchase".equals(value(coupon.get("type")))) {
                throw new IllegalStateException("First purchase coupon cannot be refunded");
            }

            Document onCompletion = document(waitlist.get("onCompletion"));
            // FAITHFUL(node-quirk): Node dereferences onCompletion without a null guard — rest-apis/modules/admin/flag.js:225
            if (truthy(onCompletion.get("isAmountRefunded"))) throw new IllegalStateException("Amount already refunded");
            if (!"completed".equals(waitlist.getString("status"))) throw new IllegalStateException("Can only refund completed sessions");

            Object consultantAmount = onCompletion.get("consultantAmount");
            mongo.getCollection(Collections.USERS).updateOne(
                    new Document("_id", strictObjectId(waitlist.get("user_id"))),
                    new Document("$inc", new Document("wallet", consultantAmount)));
            sendAddWalletNotification(waitlist.get("user_id"), consultantAmount, 1, "user");
            mongo.getCollection(Collections.CONSULTANTS).updateOne(
                    new Document("_id", strictObjectId(waitlist.get("consultant_id"))),
                    new Document("$inc", new Document("wallet", -number(consultantAmount))));
            sendAddWalletNotification(waitlist.get("consultant_id"), consultantAmount, 0, "cons");
            mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                    new Document("_id", strictObjectId(waitlistId)),
                    new Document("$set", new Document("onCompletion.isAmountRefunded", true)
                            .append("onCompletion.consultantAmount", 0))
                            .append("$push", new Document("logs", new Document("callStatus", "refunded")
                                    .append("amount", consultantAmount).append("timestamp", System.currentTimeMillis()))),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        }
        return "Successfully updated flag status!";
    }

    public List<Document> getFlags(Map<String, String> query) {
        String consultantId = required(query, "consultantId", "CONSULTANT_ID_REQUIRE");
        return mongo.getCollection(Collections.FLAG_LOGS)
                .find(new Document("consultant_id", strictObjectId(consultantId)))
                .into(new ArrayList<>());
    }

    static List<Document> listPipeline(Document filterQuery, Document sortQuery, int skip, int pageSize) {
        Document match = Document.parse("{\"$match\":{}}");
        match.put("$match", filterQuery == null ? new Document() : filterQuery);
        Document sort = Document.parse("{\"$sort\":{\"createdAt\":-1}}");
        sort.put("$sort", sortQuery == null ? new Document("createdAt", -1) : sortQuery);
        Document skipStage = Document.parse("{\"$skip\":0}");
        skipStage.put("$skip", skip);
        Document limitStage = Document.parse("{\"$limit\":1000}");
        limitStage.put("$limit", pageSize);
        return Arrays.asList(
                match,
                Document.parse("{\"$addFields\":{\"temporary_data.feedback_id\":{\"$cond\":{\"if\":{\"$ifNull\":[\"$temporary_data.feedback_id\",false]},\"then\":{\"$toObjectId\":\"$temporary_data.feedback_id\"},\"else\":null}}}}"),
                Document.parse("{\"$lookup\":{\"from\":\"consultants\",\"localField\":\"consultant_id\",\"foreignField\":\"_id\",\"as\":\"consultant_details\"}}"),
                Document.parse("{\"$unwind\":{\"path\":\"$consultant_details\",\"preserveNullAndEmptyArrays\":true}}"),
                Document.parse("{\"$lookup\":{\"from\":\"review_and_ratings\",\"localField\":\"temporary_data.feedback_id\",\"foreignField\":\"_id\",\"as\":\"feedback_details\"}}"),
                Document.parse("{\"$unwind\":{\"path\":\"$feedback_details\",\"preserveNullAndEmptyArrays\":true}}"),
                Document.parse("{\"$addFields\":{\"feedback_details.user_id\":{\"$cond\":{\"if\":{\"$ifNull\":[\"$feedback_details.user_id\",false]},\"then\":{\"$toObjectId\":\"$feedback_details.user_id\"},\"else\":null}}}}"),
                Document.parse("{\"$lookup\":{\"from\":\"users\",\"localField\":\"feedback_details.user_id\",\"foreignField\":\"_id\",\"as\":\"user_details\"}}"),
                Document.parse("{\"$unwind\":{\"path\":\"$user_details\",\"preserveNullAndEmptyArrays\":true}}"),
                Document.parse("{\"$project\":{\"consultant_id\":1,\"flag_type\":1,\"waitlist_id\":1,\"temporary_data\":1,\"flag_status\":1,\"flag_reason\":1,\"createdAt\":1,\"updatedAt\":1,\"consultant_details.name\":1,\"feedback_details.review\":1,\"feedback_details.rating\":1,\"feedback_details.feedback\":1,\"user_details.name\":1}}"),
                Document.parse("{\"$addFields\":{\"debug_feedback\":\"$feedback_details\"}}"), // FAITHFUL(node-quirk): debugging field is part of response — rest-apis/modules/admin/flag.js:121-125
                sort,
                skipStage,
                limitStage);
    }

    private Document filterQuery(Map<String, String> query) {
        String field = query.get("filterField"), operator = query.get("filterOperator"), value = query.get("filterValue");
        Document filter = new Document();
        if (!notBlank(field) || !notBlank(operator) || !notBlank(value)) return filter;
        switch (operator) {
            case "equals" -> filter.append(field, value);
            case "contains" -> filter.append(field, new Document("$regex", value).append("$options", "i"));
            case "startsWith" -> filter.append(field, new Document("$regex", "^" + value).append("$options", "i"));
            case "endsWith" -> filter.append(field, new Document("$regex", value + "$").append("$options", "i"));
            default -> { }
        }
        return filter;
    }

    private Document sortQuery(Map<String, String> query) {
        Document sort = new Document("createdAt", -1);
        String field = query.get("sortField"), direction = query.get("sortDirection");
        if (notBlank(field) && notBlank(direction) && !"null".equals(field) && !"null".equals(direction)) {
            // FAITHFUL(node-quirk): Node appends to the default createdAt sort instead of replacing it — rest-apis/modules/admin/flag.js:35-39
            sort.append(field, "asc".equals(direction.toLowerCase()) ? 1 : -1);
        }
        return sort;
    }

    private void sendAddWalletNotification(Object userId, Object coins, int transactionType, String userType) {
        try {
            String collection = "user".equals(userType) ? Collections.USERS : Collections.CONSULTANTS;
            Document userObj = mongo.getCollection(collection).find(new Document("_id", strictObjectId(userId))).first();
            if (userObj == null) return;
            Document template = walletTemplate(coins, transactionType);
            if (template == null) return;
            for (Object token : fcmTokens(userObj)) {
                push.sendNotificationAndCons(userType, value(token), template.getString("body"),
                        document(template.get("data")), template.getString("title"), "");
            }
            mongo.getCollection(Collections.NOTIFICATIONS).insertOne(new Document("_id", new ObjectId())
                    .append("receiverId", userObj.get("_id"))
                    .append("message", template.get("body"))
                    .append("title", template.get("title"))
                    .append("userType", userType)
                    .append("senderType", "system")
                    .append("type", "other")
                    .append("data", template.get("data"))
                    .append("isRead", false)
                    .append("status", true)
                    .append("createdAt", new Date())
                    .append("updatedAt", new Date()));
        } catch (Exception ignored) {
            // FAITHFUL(node-quirk): transactionService.sendAddWalletNotification swallows errors and returns false — utils/classes/transaction.js:1831-1833
        }
    }

    private Document walletTemplate(Object coins, int transactionType) {
        if (transactionType == 1) {
            if (number(coins) <= 0) return null;
            return new Document("title", "Vedic Meet")
                    .append("body", String.format(java.util.Locale.US, "%.2f Coins have been added to your wallet.", number(coins)))
                    .append("data", new Document("screen", "MyWallet").append("coins", coins));
        }
        return new Document("title", "Vedic Meet")
                .append("body", String.format(java.util.Locale.US, " %.2f Coins have been deducted from your wallet.", number(coins)))
                .append("data", new Document("screen", "MyWallet").append("coins", coins));
    }

    private List<Object> fcmTokens(Document userObj) {
        Document device = document(userObj.get("device"));
        Object tokens = device == null ? null : device.get("fcmToken");
        List<Object> out = new ArrayList<>();
        if (tokens instanceof Iterable<?> iterable) iterable.forEach(out::add);
        return out;
    }

    private Object strictObjectId(Object raw) { return raw instanceof ObjectId ? raw : new ObjectId(value(raw)); }

    private Document document(Object raw) { return raw instanceof Document d ? d : null; }

    private boolean truthy(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (raw instanceof String s) return !s.isEmpty();
        return true;
    }

    private double number(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value(raw)); } catch (Exception ignored) { return 0; }
    }

    private int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private String required(Map<?, ?> input, String key, String error) {
        Object raw = input == null ? null : input.get(key);
        if (raw == null || value(raw).isBlank()) throw new IllegalArgumentException(error);
        return value(raw);
    }

    private String value(Object raw) { return raw == null ? "" : String.valueOf(raw); }
}
