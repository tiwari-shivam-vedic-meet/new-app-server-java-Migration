package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Native port of admin/transaction.js with atomic payout import. */
@Service
public class AdminTransactionService {

    private static final Set<String> PAYOUT_HEADERS = Set.of("UserId", "Name", "Email", "DateOfJoining",
            "WithdrawAmount", "PAN", "PhoneNumber", "BankName", "BankAccountNumber", "BankIFSCCode",
            "BankHolderName", "PayoutMonth", "TransactionId", "PayoutDate");

    private final MongoTemplate mongo;
    private final AdminMongoSupport db;
    private final MediaUploadService uploads;
    private final TransactionTemplate transaction;

    public AdminTransactionService(MongoTemplate mongo, AdminMongoSupport db, MediaUploadService uploads,
                                   MongoTransactionManager transactionManager) {
        this.mongo = mongo;
        this.db = db;
        this.uploads = uploads;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> ledger(int page, int limit, String search) {
        Document filter = new Document();
        db.addSearch(filter, search, List.of("transactionFor", "userType"));
        Map<String, Object> result = db.page(Collections.WALLET_TRANSACTIONS, filter,
                new Document("createdAt", -1), page, limit);
        enrichActors(result);
        return result;
    }

    public Map<String, Object> payoutList(int page, int limit, String search, String fromDate, String toDate) {
        Document filter = new Document();
        db.addSearch(filter, search, List.of("name", "accountName", "phoneNumber"));
        if (notBlank(fromDate) && notBlank(toDate)) filter.append("payOutDate",
                new Document("$gte", date(fromDate)).append("$lte", date(toDate)));
        return db.page(Collections.CONSULTANT_PAYOUTS, filter, new Document("createdAt", -1), page, limit);
    }

    public Map<String, Object> rechargeHistory(Map<String, String> query) {
        Document filter = new Document();
        String status = query.get("statusFilter");
        if ("successful".equals(status)) filter.append("status", new Document("$in", List.of("paid", "COMPLETED")));
        else if ("failed".equals(status)) filter.append("status", new Document("$in", List.of("failed", "FAILED")));
        else if ("initiated".equals(status)) filter.append("status", new Document("$in", List.of("initiated", "INITIATED")));
        if (notBlank(query.get("supportStatusFilter"))) filter.append("supportStatus", query.get("supportStatusFilter"));
        applyDateRange(filter, query);
        db.addSearch(filter, query.get("search"), List.of("orderId", "gateway", "status"));
        Map<String, Object> result = db.page(Collections.TRANSACTIONS, filter, new Document("createdAt", -1),
                integer(query.get("page"), 1), integer(query.get("limit"), 10));
        enrichUsers(result);
        return result;
    }

    public Map<String, Object> spendingHistory(Map<String, String> query) {
        Document filter = new Document("transactionType", 1);
        if (notBlank(query.get("supportStatusFilter"))) filter.append("supportStatus", query.get("supportStatusFilter"));
        if (notBlank(query.get("userType"))) filter.append("userType", query.get("userType"));
        applyDateRange(filter, query);
        db.addSearch(filter, query.get("search"), List.of("transactionFor", "walletDeductReason"));
        Map<String, Object> result = db.page(Collections.WALLET_TRANSACTIONS, filter,
                new Document("createdAt", -1), integer(query.get("page"), 1), integer(query.get("limit"), 10));
        enrichActors(result);
        return result;
    }

    public Document updateSupportStatus(String transactionId, Map<String, Object> input) {
        Document set = new Document();
        copy(input, set, "supportStatus");
        copy(input, set, "failureType");
        copy(input, set, "supportRemarks");
        copy(input, set, "markedBy");
        if (set.isEmpty()) throw new IllegalArgumentException("SUPPORT_STATUS_REQUIRE");
        return db.updateById(Collections.TRANSACTIONS, transactionId, set, "TRANSACTION_NOT_EXIST");
    }

    public Map<String, Object> importPayout(int payoutMonth, int payoutYear, MultipartFile file) {
        validateFile(file);
        List<Map<String, String>> rows = payoutRows(read(file));
        if (rows.isEmpty()) throw new IllegalArgumentException("The file is empty.");
        Document previous = mongo.getCollection(Collections.CONSULTANT_PAYOUT_TRACKS)
                .find(new Document("month", payoutMonth).append("year", payoutYear)
                        .append("isSettlementComplete", true)).first();
        if (previous != null) throw new IllegalStateException("Settlement Already Completed!");

        String fileKey = uploads.upload(file, "payout");
        Integer inserted = transaction.execute(status -> {
            int count = 0;
            for (Map<String, String> row : rows) {
                Object consultantId = db.id(required(row, "UserId"));
                double withdraw = amount(required(row, "WithdrawAmount"));
                Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                        .find(new Document("_id", consultantId)).projection(new Document("wallet", 1)).first();
                if (consultant == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST: " + row.get("UserId"));

                boolean deducted = true;
                if (withdraw >= 1) {
                    Document changed = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                            new Document("_id", consultantId).append("wallet", new Document("$gte", withdraw)),
                            new Document("$inc", new Document("wallet", -withdraw)),
                            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                    deducted = changed != null;
                    if (deducted) {
                        mongo.getCollection(Collections.WALLET_TRANSACTIONS).insertOne(new Document("consultantId", consultantId)
                                .append("userType", "cons").append("transactionFor", "payout")
                                .append("coins", withdraw).append("amount", withdraw).append("transactionType", 1)
                                .append("createdAt", excelDate(row.get("PayoutDate")))
                                .append("walletDeductReason", "transfer wallet amount in own bank account number " + row.get("BankAccountNumber")));
                    }
                }
                mongo.getCollection(Collections.CONSULTANT_PAYOUTS).insertOne(new Document("consultantId", consultantId)
                        .append("name", row.get("Name")).append("email", row.get("Email"))
                        .append("phoneNumber", row.get("PhoneNumber")).append("dateOfJoin", excelDate(row.get("DateOfJoining")))
                        .append("monthOfPayOut", row.get("PayoutMonth")).append("monthlyPayoutAmount", withdraw)
                        .append("panNumber", row.get("PAN")).append("accountName", row.get("BankHolderName"))
                        .append("bankName", row.get("BankName")).append("accountNumber", row.get("BankAccountNumber"))
                        .append("ifsc", row.get("BankIFSCCode")).append("payOutDate", excelDate(row.get("PayoutDate")))
                        .append("transactionId", row.get("TransactionId")).append("currentMonthSavingAmount", number(consultant.get("wallet")))
                        .append("walletDeducted", deducted).append("status", true).append("createdAt", new Date()));
                count++;
            }
            mongo.getCollection(Collections.CONSULTANT_PAYOUT_TRACKS).updateOne(
                    new Document("month", payoutMonth).append("year", payoutYear),
                    new Document("$set", new Document("isSettlementComplete", true).append("sourceFile", fileKey)
                            .append("updatedAt", new Date())).append("$setOnInsert", new Document("_id", new ObjectId())
                            .append("createdAt", new Date())), new UpdateOptions().upsert(true));
            return count;
        });
        return Map.of("inserted", inserted == null ? 0 : inserted, "sourceFile", fileKey);
    }

    public String rechargeCsv(Map<String, String> query) { return csv(rechargeHistory(exportQuery(query)), false); }
    public String spendingCsv(Map<String, String> query) { return csv(spendingHistory(exportQuery(query)), true); }

    private Map<String, String> exportQuery(Map<String, String> query) {
        Map<String, String> out = new LinkedHashMap<>(query);
        out.put("page", "1"); out.put("limit", "1000");
        return out;
    }

    @SuppressWarnings("unchecked")
    private String csv(Map<String, Object> result, boolean spending) {
        List<Document> list = (List<Document>) result.getOrDefault("list", List.of());
        StringBuilder out = new StringBuilder(spending
                ? "id,actorType,transactionFor,amount,status,createdAt\n"
                : "id,userId,orderId,gateway,paidAmount,status,supportStatus,createdAt\n");
        for (Document row : list) {
            List<Object> values = spending
                    ? List.of(value(row.get("_id")), value(row.get("userType")), value(row.get("transactionFor")),
                            value(first(row.get("amount"), row.get("coins"))), value(row.get("supportStatus")), value(row.get("createdAt")))
                    : List.of(value(row.get("_id")), value(row.get("userId")), value(row.get("orderId")), value(row.get("gateway")),
                            value(row.get("paidAmount")), value(row.get("status")), value(row.get("supportStatus")), value(row.get("createdAt")));
            out.append(values.stream().map(this::quote).reduce((a, b) -> a + "," + b).orElse("")).append('\n');
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private void enrichActors(Map<String, Object> result) {
        List<Document> list = (List<Document>) result.getOrDefault("list", List.of());
        for (Document row : list) {
            String collection = "cons".equals(row.getString("userType")) ? Collections.CONSULTANTS : Collections.USERS;
            Object actorId = "cons".equals(row.getString("userType")) ? row.get("consultantId") : row.get("userId");
            Document actor = db.findById(collection, actorId);
            if (actor != null) row.put("userDetails", List.of(projection(actor)));
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichUsers(Map<String, Object> result) {
        List<Document> list = (List<Document>) result.getOrDefault("list", List.of());
        for (Document row : list) {
            Document user = db.findById(Collections.USERS, row.get("userId"));
            if (user != null) row.put("userDetails", projection(user));
        }
    }

    private Document projection(Document actor) {
        Document details = actor.get("details") instanceof Document d ? d : new Document();
        return new Document("_id", actor.get("_id")).append("name", first(actor.get("userName"), actor.get("name")))
                .append("userId", actor.get("userId")).append("email", first(details.get("email"), actor.get("email")))
                .append("phone", details.get("phone"));
    }

    private List<Map<String, String>> payoutRows(byte[] bytes) {
        List<List<String>> sheet = SimpleXlsxReader.firstSheet(bytes);
        if (sheet.isEmpty()) return List.of();
        List<String> headers = sheet.get(0);
        for (String required : PAYOUT_HEADERS) if (!headers.contains(required))
            throw new IllegalArgumentException("Missing required header: " + required);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < sheet.size(); i++) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j), j < sheet.get(i).size() ? sheet.get(i).get(j) : "");
            for (String required : PAYOUT_HEADERS) if (row.getOrDefault(required, "").isBlank())
                throw new IllegalArgumentException("Missing value for required header '" + required + "' at row " + (i + 1));
            rows.add(row);
        }
        return rows;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("No payout file uploaded.");
        if (file.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("File size exceeds the maximum limit of 10MB.");
        String type = value(file.getContentType());
        if (!Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel").contains(type))
            throw new IllegalArgumentException("Invalid file type. Only Excel files are allowed.");
    }

    private byte[] read(MultipartFile file) {
        try { return file.getBytes(); }
        catch (Exception error) { throw new IllegalArgumentException("Unable to read payout file", error); }
    }

    private void applyDateRange(Document filter, Map<String, String> query) {
        String from = query.get("timeRangeStartDate"), to = query.get("timeRangeEndDate");
        if (notBlank(from) || notBlank(to)) {
            Document range = new Document();
            if (notBlank(from)) range.append("$gte", date(from));
            if (notBlank(to)) range.append("$lte", endOfDay(to));
            filter.append("createdAt", range);
            return;
        }
        String legacy = query.get("timeFilter");
        if (!notBlank(legacy)) return;
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate start = switch (legacy.toLowerCase(Locale.ENGLISH)) {
            case "week" -> now.minusDays(7); case "month" -> now.withDayOfMonth(1); default -> now;
        };
        filter.append("createdAt", new Document("$gte", Date.from(start.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant())));
    }

    private Date excelDate(String raw) {
        try {
            double serial = Double.parseDouble(raw);
            return Date.from(LocalDate.of(1899, 12, 30).plusDays((long) serial).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (Exception ignored) { return date(raw); }
    }

    private Date date(String raw) {
        try { return Date.from(Instant.parse(raw)); }
        catch (Exception ignored) { return Date.from(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant()); }
    }
    private Date endOfDay(String raw) { return Date.from(LocalDate.parse(raw).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)); }
    private void copy(Map<String, Object> source, Document target, String key) { if (source != null && source.get(key) != null) target.put(key, source.get(key)); }
    private String required(Map<String, String> row, String key) { String v = row.get(key); if (!notBlank(v)) throw new IllegalArgumentException(key + " is required"); return v; }
    private int integer(String raw, int fallback) { try { return raw == null ? fallback : Integer.parseInt(raw); } catch (Exception ignored) { return fallback; } }
    private double amount(String raw) { double v = number(raw); if (!Double.isFinite(v) || v < 0) throw new IllegalArgumentException("INVALID_AMOUNT"); return v; }
    private double number(Object raw) { try { return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw)); } catch (Exception ignored) { return 0; } }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private Object first(Object first, Object second) { return first == null || String.valueOf(first).isBlank() ? second : first; }
    private String value(Object raw) { return raw == null ? "" : String.valueOf(raw); }
    private String quote(Object raw) { return "\"" + value(raw).replace("\"", "\"\"") + "\""; }
}
