package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.crypto.CryptoUtil;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native port of the production Node admin/user module. */
@Service
public class AdminUserService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport db;
    private final AuthDocumentCache authCache;
    private final CryptoUtil crypto;
    private final AppConstants constants;
    private final TransactionTemplate transactions;

    public AdminUserService(MongoTemplate mongo, AdminMongoSupport db, AuthDocumentCache authCache,
                            CryptoUtil crypto, AppConstants constants,
                            MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.db = db;
        this.authCache = authCache;
        this.crypto = crypto;
        this.constants = constants;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> grid(int page, int pageSize, String sortField, String sortDirection,
                                    String filterField, String filterOperator, String filterValue) {
        int limit = db.safeLimit(pageSize <= 0 ? 1000 : pageSize);
        Document filter = db.dynamicFilter(filterField, filterOperator, filterValue);
        List<Document> list = mongo.getCollection(Collections.USERS).find(filter)
                .sort(db.dynamicSort(sortField, sortDirection)).skip(Math.max(0, page) * limit)
                .limit(limit).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.USERS).countDocuments(filter);
        return Map.of("list", list, "pagination", Map.of("pageCount", (long) Math.ceil(total / (double) limit),
                "pageNumber", Math.max(0, page), "totalDocuments", total));
    }

    public Map<String, Object> users(Map<String, Object> input, boolean marketing) {
        int page = integer(input, "page", 1), limit = db.safeLimit(integer(input, "limit", 10));
        String search = text(input, "search");
        Document filter = new Document("isDeleted", false);
        db.addSearch(filter, search, List.of("name", "details.email", "details.phone", "userId"));
        Document requested = doc(input == null ? null : input.get("filter"));
        copy(requested, filter, "details.gender", "gender");
        dateFilter(requested.get("registerDate"), filter);
        if (marketing) walletRange(requested, filter);
        Document sort = userSort(text(input, "sortBy"));
        List<Document> list = mongo.getCollection(Collections.USERS).find(filter).sort(sort)
                .skip((Math.max(1, page) - 1) * limit).limit(limit).into(new ArrayList<>());
        for (Document user : list) {
            String uid = String.valueOf(user.get("_id"));
            long completed = mongo.getCollection(Collections.WAITLISTS)
                    .countDocuments(new Document("user_id", uid).append("status", "completed"));
            long attempts = mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", uid));
            user.put("totalAttempts", attempts);
            user.put("totalCompleted", completed);
            user.put("totalNumberOfConsultations", completed);
            user.put("email", nested(user, "details", "email"));
            user.put("phone", nested(user, "details", "phone"));
            addMedia(user, "profileImage");
            if (marketing) user.put(consultationCountField(text(input, "consultationCountFilter")),
                    periodConsultationCount(uid, text(input, "consultationCountFilter")));
        }
        return db.page(list, mongo.getCollection(Collections.USERS).countDocuments(filter));
    }

    public Map<String, Object> consultations(Map<String, Object> input, String kind) {
        int page = integer(input, "page", 1), limit = db.safeLimit(integer(input, "limit", 10));
        Document filter = consultationFilter(kind);
        db.addSearch(filter, text(input, "search"),
                List.of("request_form.firstName", "request_form.lastName", "request_form.phoneNumber"));
        dateFilter(doc(input == null ? null : input.get("filter")).get("registerDate"), filter);
        List<Document> list = mongo.getCollection(Collections.WAITLISTS).find(filter)
                .sort(new Document("createdAt", -1)).skip((Math.max(1, page) - 1) * limit)
                .limit(limit).into(new ArrayList<>());
        list.forEach(this::enrichWaitlist);
        return db.page(list, mongo.getCollection(Collections.WAITLISTS).countDocuments(filter));
    }

    public void cancelWaitlist(String waitlistId) {
        Object id = db.id(waitlistId);
        Document updated = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", id).append("status", new Document("$in", List.of("waiting", "initiated", "partially_accepted", "booked"))),
                new Document("$set", new Document("status", "canceled").append("updatedAt", new Date()))
                        .append("$push", new Document("logs", new Document("callStatus", "cancelled")
                                .append("message", "Cancelled by admin").append("createdAt", new Date()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("WAITLIST_NOT_EXIST_OR_ALREADY_FINAL");
    }

    public Map<String, Object> deletedUsers(Map<String, Object> input) {
        Document filter = new Document("isDeleted", true);
        db.addSearch(filter, text(input, "search"), List.of("name", "details.email", "details.phone", "userId"));
        return db.page(Collections.USERS, filter, new Document("createdAt", -1),
                integer(input, "page", 1), integer(input, "limit", 10));
    }

    public Document setStatus(String userId, boolean status) {
        Document before = db.requireById(Collections.USERS, userId, "USER_NOT_EXIST");
        Document updated = db.updateById(Collections.USERS, userId,
                new Document("status", status).append("authTokenVersion", intNumber(before.get("authTokenVersion")) + 1),
                "USER_NOT_EXIST");
        authCache.invalidate(Role.USER, before);
        authCache.invalidate(Role.USER, updated);
        return updated;
    }

    public boolean updateWallet(String userId, double amount, String action) {
        if (amount <= 0 || !Double.isFinite(amount)) throw new IllegalArgumentException("INVALID_AMOUNT");
        if (!List.of("wallet_deduct", "wallet_refund", "wallet_add").contains(action))
            throw new IllegalArgumentException("INVALID_ACTION");
        Boolean result = transactions.execute(tx -> {
            Document filter = new Document("_id", db.id(userId)).append("status", true);
            if ("wallet_deduct".equals(action)) filter.append("wallet", new Document("$gte", amount));
            double delta = "wallet_deduct".equals(action) ? -amount : amount;
            Document user = mongo.getCollection(Collections.USERS).findOneAndUpdate(filter,
                    new Document("$inc", new Document("wallet", delta)).append("$set", new Document("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (user == null) {
                Document existing = db.findById(Collections.USERS, userId);
                if (existing == null) throw new IllegalStateException("USER_NOT_FOUND");
                if (Boolean.FALSE.equals(existing.get("status"))) throw new IllegalStateException("USER_IS_BLOCKED");
                throw new IllegalStateException("INSUFFICIENT_AMOUNT");
            }
            double remaining = number(user.get("wallet"));
            mongo.getCollection(Collections.WALLETS).updateOne(new Document("userId", db.id(userId)),
                    new Document("$set", new Document("coins", crypto.encrypt(jsNumber(remaining)))
                            .append("userType", "user").append("updatedAt", new Date()))
                            .append("$setOnInsert", new Document("_id", new ObjectId()).append("savedAmount", 0)),
                    new UpdateOptions().upsert(true));
            mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(new Document("userId", db.id(userId))
                    .append("transactionFor", action).append("userType", "user").append("coins", amount)
                    .append("amount", amount).append("transactionType", "wallet_deduct".equals(action) ? 1 : 0)
                    .append("walletDeductReason", "wallet_deduct".equals(action) ? "coin deducted by admin" : null)
                    .append("createdAt", new Date()).append("updatedAt", new Date()));
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    public Map<String, Object> details(String userId) {
        Document user = db.requireById(Collections.USERS, userId, "USER_NOT_EXIST");
        addMedia(user, "profileImage");
        ObjectId id = id(user.get("_id"));
        String sid = String.valueOf(user.get("_id"));
        long calls = mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", sid));
        long extended = mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", sid)
                .append("used_for", "private_call").append("session_info.price", 0)
                .append("onCompletion.consultantAmount", new Document("$gt", 0)));
        double spent = ledgerTotal(new Document("userId", id).append("userType", "user").append("transactionType", 1));
        double refund = ledgerTotal(new Document("userId", id).append("userType", "user").append("transactionType", 2));
        user.put("totalNumberOfConsultations", calls);
        user.put("extendedCallCount", extended);
        user.put("userTotalSpending", spent);
        user.put("refundCreditedAmount", refund);
        Document wallet = mongo.getCollection(Collections.WALLETS).find(new Document("userId", id)).first();
        double coins = wallet == null ? 0 : decrypt(wallet.get("coins"));
        return Map.of("details", List.of(user), "wallet", Map.of("remainingCoins", coins,
                "savedCoins", wallet == null ? 0 : number(wallet.get("savedAmount"))));
    }

    public Map<String, Object> history(String userId, String consultantId, String type, int page, int limit) {
        if ("REMEDY".equalsIgnoreCase(type)) {
            Document filter = new Document("userId", userId).append("type", new Document("$in", List.of("REMEDY", "AREAOFCONCERN")));
            return db.page(Collections.ORDER_REMEDY_NOTES, filter, new Document("createdAt", -1), page, limit);
        }
        Document filter = "WAITLIST".equalsIgnoreCase(type)
                ? new Document("consultant_id", consultantId).append("isConsultantCompleted", "waiting")
                : new Document("user_id", userId);
        return db.page(Collections.WAITLISTS, filter, new Document("createdAt", -1), page, limit);
    }

    public void logoutAll(String userId) {
        Document before = db.requireById(Collections.USERS, userId, "USER_NOT_EXIST");
        Document emptyDevice = new Document("fcmToken", List.of()).append("deviceToken", List.of())
                .append("uuid", List.of()).append("voipToken", "");
        Document updated = db.updateById(Collections.USERS, userId,
                new Document("deviceToken", List.of()).append("fcmToken", List.of()).append("voipToken", "")
                        .append("device", emptyDevice).append("authTokenVersion", intNumber(before.get("authTokenVersion")) + 1),
                "USER_NOT_EXIST");
        authCache.invalidate(Role.USER, before);
        authCache.invalidate(Role.USER, updated);
    }

    public Map<String, Object> remedies(String type, String concernId, String remedyId) {
        List<Document> list = new ArrayList<>();
        String description = "";
        if ("AREAOFCONCERN".equals(type)) {
            mongo.getCollection(Collections.AREA_OF_CONCERNS).find(new Document("status", true)).into(list);
        } else if ("REMEDY".equals(type)) {
            mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES)
                    .find(new Document("areaOfConcernId", db.id(concernId)).append("status", true)).into(list);
        } else if ("DESCRIPTION".equals(type)) {
            Document remedy = db.findById(Collections.AREA_OF_CONCERN_REMEDIES, remedyId);
            if (remedy != null && !Boolean.FALSE.equals(remedy.get("status"))) description = value(remedy.get("description"));
        }
        return Map.of("list", list, "description", description);
    }

    public Document addRemedy(Map<String, Object> body) {
        String title = AdminResponses.text(body, "title");
        if (mongo.getCollection(Collections.ORDER_REMEDY_NOTES).find(new Document("title", title)).first() != null)
            throw new IllegalStateException("TITLE_EXIST");
        Document document = new Document(body).append("createdAt", new Date()).append("updatedAt", new Date());
        mongo.getCollection(Collections.ORDER_REMEDY_NOTES).insertOne(document);
        return document;
    }

    public Map<String, Object> numericalAnalytics(String userId) {
        Document user = db.requireById(Collections.USERS, userId, "USER_NOT_EXIST");
        String sid = String.valueOf(user.get("_id"));
        long total = mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", sid));
        long chat = mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", sid).append("session_info.mode", "chat"));
        long call = mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", sid)
                .append("session_info.mode", new Document("$in", List.of("audio", "video"))));
        double spent = ledgerTotal(new Document("userId", user.get("_id")).append("transactionType", 1));
        return Map.of("totalNumberOfConsultations", total, "totalConsultationsViaChat", chat,
                "totalConsultationsViaCall", call, "totalAmountSpent", spent);
    }

    public Map<String, Object> walletTransactions(String userId, int page, int limit) {
        return db.page(Collections.WALLET_TRANSACTIONS, new Document("userId", db.id(userId)),
                new Document("createdAt", -1), page, limit);
    }

    public Map<String, Object> counts(String adminId, boolean debug) {
        Map<String, Document> kinds = entityFilters();
        Map<String, Object> unread = new LinkedHashMap<>(), total = new LinkedHashMap<>();
        for (Map.Entry<String, Document> entry : kinds.entrySet()) {
            String collection = "support_query".equals(entry.getKey()) ? Collections.CUSTOMER_SUPPORT_QUERIES : Collections.WAITLISTS;
            long count = mongo.getCollection(collection).countDocuments(entry.getValue());
            long read = unreadReadCount(adminId, entry.getKey(), collection, entry.getValue());
            unread.put(countKey(entry.getKey()), Math.max(0, count - read));
            total.put(entry.getKey(), count);
        }
        if (!debug) return unread;
        return Map.of("adminId", adminId, "total", total, "unread", unread);
    }

    public Map<String, Object> markAllUnread(String adminId) {
        int waits = markEntities(adminId, "waitlist", Collections.WAITLISTS, new Document(), false);
        int supports = markEntities(adminId, "support_query", Collections.CUSTOMER_SUPPORT_QUERIES, new Document(), false);
        return Map.of("totalItems", waits + supports, "waitlistItems", waits, "supportItems", supports);
    }

    public int markRead(String adminId, String entityType, List<?> entityIds) {
        if (entityType == null || entityIds == null) throw new IllegalArgumentException("entityType and entityIds array are required");
        Date now = new Date();
        List<UpdateOneModel<Document>> writes = entityIds.stream().map(raw -> new UpdateOneModel<Document>(
                new Document("adminId", db.id(adminId)).append("entityType", entityType).append("entityId", db.id(raw)),
                new Document("$set", new Document("isRead", true).append("readAt", now).append("lastViewedAt", now)),
                new UpdateOptions().upsert(true))).toList();
        if (!writes.isEmpty()) mongo.getCollection(Collections.ADMIN_READ_TRACKINGS).bulkWrite(writes);
        return writes.size();
    }

    public int markAllRead(String adminId, String entityType, Map<String, Object> rawFilter) {
        String collection = entityCollection(entityType);
        return markEntities(adminId, entityType, collection, doc(rawFilter), true);
    }

    public String consultationCsv(Map<String, Object> input, String kind) {
        Map<String, Object> exportInput = new LinkedHashMap<>(input == null ? Map.of() : input);
        exportInput.put("page", 1);
        exportInput.put("limit", 1000);
        @SuppressWarnings("unchecked") List<Document> list = (List<Document>) consultations(exportInput, kind).get("list");
        StringBuilder csv = new StringBuilder("waitlistId,userId,consultantId,status,mode,createdAt\n");
        for (Document row : list) csv.append(csv(row.get("_id"))).append(',').append(csv(row.get("user_id")))
                .append(',').append(csv(row.get("consultant_id"))).append(',').append(csv(row.get("status")))
                .append(',').append(csv(nested(row, "session_info", "mode"))).append(',')
                .append(csv(row.get("createdAt"))).append('\n');
        return csv.toString();
    }

    public String usersCsv(boolean deleted) {
        List<Document> list = mongo.getCollection(Collections.USERS)
                .find(new Document("isDeleted", deleted)).sort(new Document("createdAt", -1))
                .limit(10000).into(new ArrayList<>());
        StringBuilder csv = new StringBuilder("userId,name,phone,email,gender,wallet,registeredAt\n");
        for (Document user : list) csv.append(csv(user.get("userId"))).append(',')
                .append(csv(user.get("name"))).append(',').append(csv(nested(user, "details", "phone")))
                .append(',').append(csv(nested(user, "details", "email"))).append(',')
                .append(csv(nested(user, "details", "gender"))).append(',').append(csv(user.get("wallet")))
                .append(',').append(csv(user.get("createdAt"))).append('\n');
        return csv.toString();
    }

    private int markEntities(String adminId, String type, String collection, Document filter, boolean read) {
        List<Document> entities = mongo.getCollection(collection).find(filter).projection(new Document("_id", 1)).into(new ArrayList<>());
        Date now = new Date();
        List<UpdateOneModel<Document>> writes = entities.stream().map(entity -> new UpdateOneModel<Document>(
                new Document("adminId", db.id(adminId)).append("entityType", type).append("entityId", entity.get("_id")),
                new Document("$set", new Document("isRead", read).append("readAt", read ? now : null).append("lastViewedAt", now)),
                new UpdateOptions().upsert(true))).toList();
        if (!writes.isEmpty()) mongo.getCollection(Collections.ADMIN_READ_TRACKINGS).bulkWrite(writes);
        return writes.size();
    }

    private long unreadReadCount(String adminId, String type, String collection, Document filter) {
        List<Object> ids = mongo.getCollection(collection).find(filter).projection(new Document("_id", 1))
                .map(d -> d.get("_id")).into(new ArrayList<>());
        if (ids.isEmpty()) return 0;
        return mongo.getCollection(Collections.ADMIN_READ_TRACKINGS).countDocuments(new Document("adminId", db.id(adminId))
                .append("entityType", type).append("entityId", new Document("$in", ids)).append("isRead", true));
    }

    private Map<String, Document> entityFilters() {
        Map<String, Document> map = new LinkedHashMap<>();
        map.put("waitlist", new Document("status", "waiting"));
        map.put("failed_consultation", new Document("status", "failed"));
        map.put("missed_call", new Document("status", "missed"));
        map.put("progress_call", new Document("status", "progress"));
        map.put("completed_call", new Document("status", "completed"));
        map.put("booked_session", new Document("status", "booked"));
        map.put("support_query", new Document("isResolved", false));
        return map;
    }

    private String entityCollection(String type) {
        if ("support_query".equals(type)) return Collections.CUSTOMER_SUPPORT_QUERIES;
        if (entityFilters().containsKey(type)) return Collections.WAITLISTS;
        throw new IllegalArgumentException("Invalid entity type");
    }

    private String countKey(String type) {
        return switch (type) {
            case "waitlist" -> "waitlistCount"; case "failed_consultation" -> "failedCount";
            case "missed_call" -> "missedCount"; case "progress_call" -> "progressCount";
            case "completed_call" -> "completedCount"; case "booked_session" -> "bookedCount";
            case "support_query" -> "supportCount"; default -> type;
        };
    }

    private Document consultationFilter(String kind) {
        return switch (kind.toLowerCase(Locale.ENGLISH)) {
            case "waiting", "waitlist" -> new Document("status", "waiting");
            case "failed" -> new Document("status", "canceled").append("logs", new Document("$elemMatch", new Document("callStatus", "cancelled")));
            case "missed" -> new Document("status", "missed");
            case "completed" -> new Document("status", "completed");
            case "progress" -> new Document("status", "progress");
            case "booked" -> new Document("status", "booked");
            default -> new Document();
        };
    }

    private void enrichWaitlist(Document row) {
        Document user = db.findById(Collections.USERS, row.get("user_id"));
        Document consultant = db.findById(Collections.CONSULTANTS, row.get("consultant_id"));
        if (user != null) row.put("userDetails", actor(user));
        if (consultant != null) row.put("consultantDetails", actor(consultant));
    }

    private Document actor(Document actor) {
        return new Document("_id", actor.get("_id")).append("userId", actor.get("userId"))
                .append("name", actor.get("name")).append("accountName", actor.get("accountName"))
                .append("mobile", nested(actor, "details", "phone")).append("email", nested(actor, "details", "email"));
    }

    private double ledgerTotal(Document filter) {
        List<Document> result = mongo.getCollection(Collections.WALLET_TRANSACTIONS).aggregate(List.of(
                new Document("$match", filter),
                new Document("$group", new Document("_id", null).append("total", new Document("$sum",
                        new Document("$convert", new Document("input", "$amount").append("to", "double").append("onError", 0).append("onNull", 0)))))))
                .into(new ArrayList<>());
        return result.isEmpty() ? 0 : number(result.get(0).get("total"));
    }

    private long periodConsultationCount(String userId, String period) {
        Instant start = switch (value(period).toLowerCase(Locale.ENGLISH)) {
            case "weekly" -> Instant.now().minusSeconds(7L * 86400);
            case "monthly" -> Instant.now().minusSeconds(30L * 86400);
            case "yearly" -> Instant.now().minusSeconds(365L * 86400);
            default -> LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        };
        return mongo.getCollection(Collections.WAITLISTS).countDocuments(new Document("user_id", userId)
                .append("status", "completed").append("createdAt", new Document("$gte", Date.from(start))));
    }

    private String consultationCountField(String period) {
        return switch (value(period).toLowerCase(Locale.ENGLISH)) {
            case "weekly" -> "consultationsPerWeek"; case "monthly" -> "consultationsPerMonth";
            case "yearly" -> "consultationsPerYear"; default -> "consultationsPerDay";
        };
    }

    private void walletRange(Document requested, Document filter) {
        Document range = doc(requested.get("walletRange"));
        String mode = value(requested.get("selectedModeWallet"));
        if (range.isEmpty() || mode.isBlank()) return;
        double min = number(range.get("min")), max = number(range.get("max"));
        Document condition = switch (mode) {
            case "below" -> new Document("$lt", min); case "above" -> new Document("$gt", min);
            case "between" -> new Document("$gte", min).append("$lte", max); default -> new Document("$eq", min);
        };
        filter.append("wallet", condition);
    }

    private Document userSort(String sortBy) {
        return switch (value(sortBy)) {
            case "wallet_desc" -> new Document("wallet", -1); case "wallet_asc" -> new Document("wallet", 1);
            case "name_asc" -> new Document("name", 1); case "name_desc" -> new Document("name", -1);
            case "registration_asc" -> new Document("createdAt", 1); default -> new Document("createdAt", -1);
        };
    }

    private void dateFilter(Object raw, Document filter) {
        if (raw == null || String.valueOf(raw).isBlank()) return;
        LocalDate date = LocalDate.parse(String.valueOf(raw));
        Instant start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        filter.append("createdAt", new Document("$gte", Date.from(start)).append("$lt", Date.from(start.plusSeconds(86400))));
    }

    private void copy(Document source, Document target, String targetKey, String sourceKey) {
        if (source.get(sourceKey) != null && !String.valueOf(source.get(sourceKey)).isBlank()) target.put(targetKey, source.get(sourceKey));
    }

    private void addMedia(Document document, String key) {
        String value = value(document.get(key));
        if (!value.isBlank() && !value.startsWith("http")) document.put(key, constants.mediaUrl + value);
    }

    private Object nested(Document root, String parent, String child) {
        Object value = root.get(parent);
        if (value instanceof Document document) return document.get(child);
        if (value instanceof Map<?, ?> map) return map.get(child);
        return null;
    }

    private Document doc(Object raw) {
        if (raw instanceof Document document) return new Document(document);
        if (raw instanceof Map<?, ?> map) {
            Document document = new Document();
            map.forEach((key, value) -> document.put(String.valueOf(key), value));
            return document;
        }
        return new Document();
    }

    private int integer(Map<String, Object> input, String key, int fallback) {
        return AdminResponses.integer(input == null ? null : input.get(key), fallback);
    }
    private String text(Map<String, Object> input, String key) { return value(input == null ? null : input.get(key)); }
    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }
    private int intNumber(Object value) { return (int) number(value); }
    private ObjectId id(Object raw) { return raw instanceof ObjectId oid ? oid : new ObjectId(String.valueOf(raw)); }
    private double decrypt(Object cipher) {
        if (cipher == null || String.valueOf(cipher).isBlank()) return 0;
        try { return Double.parseDouble(crypto.decrypt(String.valueOf(cipher))); }
        catch (Exception ignored) { return 0; }
    }
    private String jsNumber(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }
    private String value(Object raw) { return raw == null ? "" : String.valueOf(raw); }
    private String csv(Object raw) { return "\"" + value(raw).replace("\"", "\"\"") + "\""; }
}
